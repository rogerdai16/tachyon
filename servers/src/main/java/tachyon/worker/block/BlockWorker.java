/*
 * Licensed to the University of California, Berkeley under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package tachyon.worker.block;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.common.base.Throwables;

import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.server.TThreadPoolServer;
import org.apache.thrift.transport.TFramedTransport;
import org.apache.thrift.transport.TServerSocket;
import org.apache.thrift.transport.TTransportException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tachyon.Constants;
import tachyon.Users;
import tachyon.conf.TachyonConf;
import tachyon.exception.AlreadyExistsException;
import tachyon.exception.OutOfSpaceException;
import tachyon.metrics.MetricsSystem;
import tachyon.thrift.NetAddress;
import tachyon.thrift.WorkerService;
import tachyon.util.CommonUtils;
import tachyon.util.io.PathUtils;
import tachyon.util.network.NetworkAddressUtils;
import tachyon.util.ThreadFactoryUtils;
import tachyon.web.UIWebServer;
import tachyon.web.WorkerUIWebServer;
import tachyon.worker.DataServer;
import tachyon.worker.WorkerSource;

/**
 * The class is responsible for managing all top level components of the Block Worker, including:
 *
 * Servers: BlockServiceHandler (RPC Server), BlockDataServer (Data Server)
 *
 * Periodic Threads: BlockMasterSync (Worker to Master continuous communication)
 *
 * Logic: BlockDataManager (Logic for all block related storage operations)
 */
public class BlockWorker {
  private static final Logger LOG = LoggerFactory.getLogger(Constants.LOGGER_TYPE);

  /** Runnable responsible for heartbeating and registration with master. */
  private final BlockMasterSync mBlockMasterSync;
  /** Runnable responsible for fetching pinlist from master. */
  private final PinListSync mPinListSync;
  /** Runnable responsible for clean up potential zombie users. */
  private final UserCleaner mUserCleanerThread;
  /** Logic for handling RPC requests. */
  private final BlockServiceHandler mServiceHandler;
  /** Logic for managing block store and under file system store. */
  private final BlockDataManager mBlockDataManager;
  /** Server for data requests and responses. */
  private final DataServer mDataServer;
  /** Threadpool for the master sync */
  private final ExecutorService mSyncExecutorService;
  /** Net address of this worker */
  private final NetAddress mWorkerNetAddress;
  /** Configuration object */
  private final TachyonConf mTachyonConf;
  /** Server socket for thrift */
  private final TServerSocket mThriftServerSocket;
  /** Thread pool for thrift */
  private final TThreadPoolServer mThriftServer;
  /** Worker start time in milliseconds */
  private final long mStartTimeMs;
  /** Worker Web UI server */
  private final UIWebServer mWebServer;
  /** Worker metrics system */
  private MetricsSystem mWorkerMetricsSystem;

  /**
   * Creates a Tachyon Block Worker.
   *
   * @param tachyonConf the configuration values to be used
   * @throws IOException for other exceptions
   */
  public BlockWorker(TachyonConf tachyonConf) throws IOException {
    mTachyonConf = tachyonConf;
    mStartTimeMs = System.currentTimeMillis();

    // Set up BlockDataManager
    WorkerSource workerSource = new WorkerSource();
    mBlockDataManager = new BlockDataManager(tachyonConf, workerSource);

    // Setup metrics collection
    mWorkerMetricsSystem = new MetricsSystem("worker", mTachyonConf);
    workerSource.registerGauges(mBlockDataManager);
    mWorkerMetricsSystem.registerSource(workerSource);

    // Set up DataServer
    int dataServerPort =
        tachyonConf.getInt(Constants.WORKER_DATA_PORT, Constants.DEFAULT_WORKER_DATA_SERVER_PORT);
    InetSocketAddress dataServerAddress =
        new InetSocketAddress(NetworkAddressUtils.getLocalHostName(tachyonConf), dataServerPort);
    mDataServer =
        DataServer.Factory.createDataServer(dataServerAddress, mBlockDataManager, mTachyonConf);

    // Setup RPC Server
    mServiceHandler = new BlockServiceHandler(mBlockDataManager);
    mThriftServerSocket = createThriftServerSocket();
    int thriftServerPort = NetworkAddressUtils.getPort(mThriftServerSocket);
    mThriftServer = createThriftServer();
    mWorkerNetAddress =
        new NetAddress(NetworkAddressUtils.getLocalWorkerAddress(mTachyonConf).getAddress()
            .getCanonicalHostName(), thriftServerPort, mDataServer.getPort());

    // Set up web server
    int webPort = mTachyonConf.getInt(Constants.WORKER_WEB_PORT, Constants.DEFAULT_WORKER_WEB_PORT);
    mWebServer =
        new WorkerUIWebServer("Tachyon Worker", new InetSocketAddress(mWorkerNetAddress.getMHost(),
            webPort), mBlockDataManager, NetworkAddressUtils.getLocalWorkerAddress(mTachyonConf),
            mStartTimeMs, mTachyonConf);

    // Setup Worker to Master Syncer
    // We create three threads for two syncers and one cleaner: mBlockMasterSync,
    // mPinListSync and mUserCleanerThread
    mSyncExecutorService =
        Executors.newFixedThreadPool(3, ThreadFactoryUtils.build("worker-heartbeat-%d", true));
    mBlockMasterSync = new BlockMasterSync(mBlockDataManager, mTachyonConf, mWorkerNetAddress);
    mBlockMasterSync.registerWithMaster();

    // Setup PinListSyncer
    mPinListSync = new PinListSync(mBlockDataManager, mTachyonConf);

    // Setup UserCleaner
    mUserCleanerThread = new UserCleaner(mBlockDataManager, mTachyonConf);

    // Setup user metadata mapping
    // TODO: Have a top level register that gets the worker id.
    long workerId = mBlockMasterSync.getWorkerId();
    String tachyonHome = mTachyonConf.get(Constants.TACHYON_HOME, Constants.DEFAULT_HOME);
    String ufsAddress =
        mTachyonConf.get(Constants.UNDERFS_ADDRESS, tachyonHome + "/underFSStorage");
    String ufsWorkerFolder =
        mTachyonConf.get(Constants.UNDERFS_WORKERS_FOLDER, ufsAddress + "/tachyon/workers");
    Users users = new Users(PathUtils.concatPath(ufsWorkerFolder, workerId), mTachyonConf);

    // Give BlockDataManager a pointer to the user metadata mapping
    // TODO: Fix this hack when we have a top level register
    mBlockDataManager.setUsers(users);
    mBlockDataManager.setWorkerId(workerId);
  }

  /**
   * Gets this worker's {@link tachyon.thrift.NetAddress}, which is the worker's hostname, rpc
   * server port, and data server port
   *
   * @return the worker's net address
   */
  public NetAddress getWorkerNetAddress() {
    return mWorkerNetAddress;
  }

  /**
   * Runs the block worker. The thread calling this will be blocked until the thrift server shuts
   * down.
   */
  public void process() {
    mWorkerMetricsSystem.start();

    // Add the metrics servlet to the web server, this must be done after the metrics system starts
    mWebServer.addHandler(mWorkerMetricsSystem.getServletHandler());

    mSyncExecutorService.submit(mBlockMasterSync);

    // Start the pinlist syncer to perform the periodical fetching
    mSyncExecutorService.submit(mPinListSync);

    // Start the user cleanup checker to perform the periodical checking
    mSyncExecutorService.submit(mUserCleanerThread);

    mWebServer.startWebServer();
    mThriftServer.serve();
  }

  /**
   * Stops the block worker. This method should only be called to terminate the worker.
   *
   * @throws IOException if the data server fails to close.
   */
  public void stop() throws IOException {
    mDataServer.close();
    mThriftServer.stop();
    mThriftServerSocket.close();
    mBlockMasterSync.stop();
    mPinListSync.stop();
    mUserCleanerThread.stop();
    mSyncExecutorService.shutdown();
    try {
      mWebServer.shutdownWebServer();
    } catch (Exception e) {
      LOG.error("Failed to stop web server", e);
    }
    mBlockDataManager.stop();
    while (!mDataServer.isClosed() || mThriftServer.isServing()) {
      // TODO: The reason to stop and close again is due to some issues in Thrift.
      mDataServer.close();
      mThriftServer.stop();
      mThriftServerSocket.close();
      CommonUtils.sleepMs(null, 100);
    }
  }

  /**
   * Helper method to create a {@link org.apache.thrift.server.TThreadPoolServer} for handling
   * incoming RPC requests.
   *
   * @return a thrift server
   */
  private TThreadPoolServer createThriftServer() {
    int minWorkerThreads =
        mTachyonConf.getInt(Constants.WORKER_MIN_WORKER_THREADS, Runtime.getRuntime()
            .availableProcessors());
    int maxWorkerThreads =
        mTachyonConf.getInt(Constants.WORKER_MAX_WORKER_THREADS,
            Constants.DEFAULT_WORKER_MAX_WORKER_THREADS);
    WorkerService.Processor<BlockServiceHandler> processor =
        new WorkerService.Processor<BlockServiceHandler>(mServiceHandler);
    return new TThreadPoolServer(new TThreadPoolServer.Args(mThriftServerSocket)
        .minWorkerThreads(minWorkerThreads).maxWorkerThreads(maxWorkerThreads).processor(processor)
        .transportFactory(new TFramedTransport.Factory())
        .protocolFactory(new TBinaryProtocol.Factory(true, true)));
  }

  /**
   * Helper method to create a {@link org.apache.thrift.transport.TServerSocket} for the RPC server
   *
   * @return a thrift server socket
   */
  private TServerSocket createThriftServerSocket() {
    try {
      return new TServerSocket(NetworkAddressUtils.getLocalWorkerAddress(mTachyonConf));
    } catch (TTransportException tte) {
      LOG.error(tte.getMessage(), tte);
      throw Throwables.propagate(tte);
    }
  }

  // For unit test purposes only
  public BlockServiceHandler getWorkerServiceHandler() {
    return mServiceHandler;
  }
}
