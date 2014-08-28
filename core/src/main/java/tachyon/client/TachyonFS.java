/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package tachyon.client;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.log4j.Logger;
import org.apache.thrift.TException;

import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;

import tachyon.Constants;
import tachyon.StorageId;
import tachyon.UnderFileSystem;
import tachyon.client.table.RawTable;
import tachyon.conf.CommonConf;
import tachyon.conf.UserConf;
import tachyon.master.MasterClient;
import tachyon.thrift.BlockInfoException;
import tachyon.thrift.ClientBlockInfo;
import tachyon.thrift.ClientDependencyInfo;
import tachyon.thrift.ClientFileInfo;
import tachyon.thrift.ClientRawTableInfo;
import tachyon.thrift.ClientWorkerInfo;
import tachyon.thrift.FileDoesNotExistException;
import tachyon.thrift.InvalidPathException;
import tachyon.thrift.NetAddress;
import tachyon.thrift.NoWorkerException;
import tachyon.thrift.TachyonException;
import tachyon.thrift.WorkerDirInfo;
import tachyon.thrift.WorkerFileInfo;
import tachyon.util.CommonUtils;
import tachyon.util.NetworkUtils;
import tachyon.worker.WorkerClient;

/**
 * Tachyon's user client API. It contains a MasterClient and several WorkerClients
 * depending on how many workers the client program is interacting with.
 */
public class TachyonFS {
  /**
   * Create a TachyonFS handler.
   * 
   * @param tachyonPath
   *          a Tachyon path contains master address. e.g., tachyon://localhost:19998,
   *          tachyon://localhost:19998/ab/c.txt
   * @return the corresponding TachyonFS handler
   * @throws IOException
   */
  public static synchronized TachyonFS get(final String tachyonPath) throws IOException {
    boolean zookeeperMode = false;
    String tempAddress = tachyonPath;
    if (tachyonPath.startsWith(Constants.HEADER)) {
      tempAddress = tachyonPath.substring(Constants.HEADER.length());
    } else if (tachyonPath.startsWith(Constants.HEADER_FT)) {
      zookeeperMode = true;
      tempAddress = tachyonPath.substring(Constants.HEADER_FT.length());
    } else {
      throw new IOException("Invalid Path: " + tachyonPath + ". Use " + Constants.HEADER
          + "host:port/ ," + Constants.HEADER_FT + "host:port/");
    }
    String masterAddress = tempAddress;
    if (tempAddress.contains(Constants.PATH_SEPARATOR)) {
      masterAddress = tempAddress.substring(0, tempAddress.indexOf(Constants.PATH_SEPARATOR));
    }
    Preconditions.checkArgument(masterAddress.split(":").length == 2,
        "Illegal Tachyon Master Address: " + tachyonPath);

    String masterHost = masterAddress.split(":")[0];
    int masterPort = Integer.parseInt(masterAddress.split(":")[1]);
    return new TachyonFS(new InetSocketAddress(masterHost, masterPort), zookeeperMode);
  }

  /**
   * Create a TachyonFS handler.
   * 
   * @param masterHost
   *          master host details
   * @param masterPort
   *          port master listens on
   * @param zookeeperMode
   *          use zookeeper
   * 
   * @return the corresponding TachyonFS hanlder
   * @throws IOException
   */
  public static synchronized TachyonFS
      get(String masterHost, int masterPort, boolean zookeeperMode) throws IOException {
    return new TachyonFS(new InetSocketAddress(masterHost, masterPort), zookeeperMode);
  }

  private final Logger LOG = Logger.getLogger(Constants.LOGGER_TYPE);
  private final long USER_QUOTA_UNIT_BYTES = UserConf.get().QUOTA_UNIT_BYTES;

  // The RPC client talks to the system master.
  private MasterClient mMasterClient = null;
  // The Master address.
  private InetSocketAddress mMasterAddress = null;
  // Whether use ZooKeeper or not
  private final boolean mZookeeperMode;
  // Cached ClientFileInfo
  private final Map<String, ClientFileInfo> mCachedClientFileInfos =
      new HashMap<String, ClientFileInfo>();
  private final Map<Integer, ClientFileInfo> mClientFileInfos =
      new HashMap<Integer, ClientFileInfo>();
  // Cached ClientBlockInfo
  // private Map<Long, ClientBlockInfo> mClientBlockInfos = new HashMap<Long, ClientBlockInfo>();
  // The RPC client talks to the local worker if there is one.
  private WorkerClient mWorkerClient = null;
  // Whether the client is local or remote.
  private boolean mIsWorkerLocal = false;
  // The local root data folder.
  private String mLocalDataFolder;
  // The local data folder.
  private String mUserTempFolder = null;
  // The HDFS data folder
  private String mUserUnderfsTempFolder = null;

  private UnderFileSystem mUnderFileSystem = null;

  // The user id of the client.
  private long mUserId = 0;
  // All Blocks has been locked.
  private final Map<Long, Set<Integer>> mLockedBlockIds = new HashMap<Long, Set<Integer>>();

  // Each user facing block has a unique block lock id.
  private final AtomicInteger mBlockLockId = new AtomicInteger(0);

  private final Map<Long, WorkerDirInfo> mIdToDirInfo = new HashMap<Long, WorkerDirInfo>();
  private final Map<Long, UnderFileSystem> mIdToUFS = new HashMap<Long, UnderFileSystem>();
  private final Map<Long, Long> mAvailableSpaceBytes = new HashMap<Long, Long>();

  private boolean mConnected = false;

  private TachyonFS(InetSocketAddress masterAddress, boolean zookeeperMode) {
    mMasterAddress = masterAddress;
    mZookeeperMode = zookeeperMode;
  }

  /**
   * Update the latest block access time on the worker.
   * 
   * @param blockId
   *          the local block's id
   * @throws IOException
   */
  private synchronized void accessLocalBlock(long blockId) throws IOException {
    if (hasLocalWorker()) {
      try {
        mWorkerClient.accessBlock(blockId);
        return;
      } catch (TException e) {
        mWorkerClient = null;
        LOG.error(e.getMessage(), e);
      }
    }

    LOG.error("TachyonClient accessLocalBlock(" + blockId + ") failed");
  }

  /**
   * Notify the worker that the checkpoint file of the file FID has been added.
   * 
   * @param fid
   *          the file id
   * @throws IOException
   */
  synchronized void addCheckpoint(int fid) throws IOException {
    connect();
    if (!mConnected) {
      throw new IOException("Failed to add checkpoint for file " + fid);
    }
    if (mWorkerClient != null) {
      try {
        mWorkerClient.addCheckpoint(mUserId, fid);
      } catch (TException e) {
        LOG.error(e.getMessage(), e);
        mWorkerClient = null;
        throw new IOException(e);
      }
    }
  }

  /**
   * Notify the worker to checkpoint the file asynchronously
   * 
   * @param fid
   *          the file id
   * @return true if succeed, false otherwise
   * @throws IOException
   */
  synchronized boolean asyncCheckpoint(int fid) throws IOException {
    connect();
    try {
      return mWorkerClient.asyncCheckpoint(fid);
    } catch (TachyonException e) {
      throw new IOException(e);
    } catch (TException e) {
      mConnected = false;
      throw new IOException(e);
    }
  }

  /**
   * Notify the worker the block is cached.
   * 
   * @param storageId
   *          the storage id of the Dir that the block is cached into
   * @param blockId
   *          the block id
   * @throws IOException
   */
  public synchronized void cacheBlock(long storageId, long blockId) throws IOException {
    connect();
    if (!mConnected) {
      return;
    }

    if (mWorkerClient != null) {
      try {
        mWorkerClient.cacheBlock(storageId, mUserId, blockId);
      } catch (TException e) {
        LOG.error(e.getMessage(), e);
        mWorkerClient = null;
        throw new IOException(e);
      }
    }
  }

  /**
   * Cleans the given path, throwing an IOException rather than an InvalidPathException.
   * 
   * @param path
   *          The path to clean
   * @return the cleaned path
   */
  private synchronized String cleanPathIOException(String path) throws IOException {
    try {
      return CommonUtils.cleanPath(path);
    } catch (InvalidPathException e) {
      throw new IOException(e.getMessage());
    }
  }

  /**
   * Close the client. Close the connections to both to master and worker
   * 
   * @throws TException
   */
  public synchronized void close() throws TException {
    if (mMasterClient != null) {
      mMasterClient.cleanConnect();
    }

    if (mWorkerClient != null && mWorkerClient.isConnected()) {
      for (Entry<Long, Long> entry : mAvailableSpaceBytes.entrySet()) {
        mWorkerClient.returnSpace(entry.getKey(), mUserId, entry.getValue());
      }
      mWorkerClient.close();
    }
  }

  /**
   * The file is complete.
   * 
   * @param fId
   *          the file id
   * @throws IOException
   */
  synchronized void completeFile(int fId) throws IOException {
    connect();
    try {
      mMasterClient.user_completeFile(fId);
    } catch (TException e) {
      mConnected = false;
      throw new IOException(e);
    }
  }

  // Lazy connection TODO This should be removed since the Thrift server has been fixed.
  private synchronized void connect() throws IOException {
    if (mMasterClient != null) {
      return;
    }
    LOG.info("Trying to connect master @ " + mMasterAddress);
    mMasterClient = new MasterClient(mMasterAddress, mZookeeperMode);

    try {
      mMasterClient.connect();
      mConnected = true;
    } catch (TException e) {
      throw new IOException(e.getMessage(), e);
    }

    try {
      mUserId = mMasterClient.getUserId();
    } catch (TException e) {
      LOG.error(e.getMessage());
      mConnected = false;
      return;
    }

    InetSocketAddress workerAddress = null;
    NetAddress workerNetAddress = null;
    mIsWorkerLocal = false;
    try {
      String localHostName;
      try {
        localHostName =
            NetworkUtils.resolveHostName(InetAddress.getLocalHost().getCanonicalHostName());
      } catch (UnknownHostException e) {
        localHostName = InetAddress.getLocalHost().getCanonicalHostName();
      }
      LOG.info("Trying to get local worker host : " + localHostName);
      workerNetAddress = mMasterClient.user_getWorker(false, localHostName);
      mIsWorkerLocal = true;
    } catch (NoWorkerException e) {
      LOG.info(e.getMessage());
      workerNetAddress = null;
    } catch (UnknownHostException e) {
      LOG.error(e.getMessage());
      workerNetAddress = null;
    } catch (TException e) {
      LOG.error(e.getMessage());
      mConnected = false;
      workerNetAddress = null;
    }

    if (workerNetAddress == null) {
      try {
        workerNetAddress = mMasterClient.user_getWorker(true, "");
      } catch (NoWorkerException e) {
        LOG.info(e.getMessage());
        workerNetAddress = null;
      } catch (TException e) {
        LOG.error(e.getMessage());
        mConnected = false;
        workerNetAddress = null;
      }
    }

    if (workerNetAddress == null) {
      LOG.info("No worker running in the system");
      return;
    }

    workerAddress = new InetSocketAddress(workerNetAddress.mHost, workerNetAddress.mPort);

    LOG.info("Connecting " + (mIsWorkerLocal ? "local" : "remote") + " worker @ " + workerAddress);
    mWorkerClient = new WorkerClient(workerAddress, mUserId);
    if (!mWorkerClient.open()) {
      LOG.error("Failed to connect " + (mIsWorkerLocal ? "local" : "remote") + " worker @ "
          + workerAddress);
      mWorkerClient = null;
      return;
    }

    try {
      mLocalDataFolder = mWorkerClient.getDataFolder();
      mUserTempFolder = mWorkerClient.getUserTempFolder(mUserId);
      mUserUnderfsTempFolder = mWorkerClient.getUserUnderfsTempFolder(mUserId);
    } catch (TException e) {
      LOG.error(e.getMessage());
      mLocalDataFolder = null;
      mUserTempFolder = null;
      mWorkerClient = null;
    }
  }

  /**
   * Create a user local temporary folder in the specified Dir and return it
   * 
   * @param storageId
   *          the storage id of the Dir
   * @return the local temporary folder
   * @throws IOException
   */
  public synchronized String createAndGetUserTempFolder(long storageId) throws IOException {
    connect();

    if (mUserTempFolder == null) {
      return null;
    }
    WorkerDirInfo dirInfo;
    if (mIdToDirInfo.containsKey(storageId)) {
      dirInfo = mIdToDirInfo.get(storageId);
    } else {
      dirInfo = getDirInfoByStorageId(storageId);
      if (dirInfo == null) {
        LOG.error("not found storage dir! storageId:" + storageId);
        return null;
      } else {
        mIdToDirInfo.put(storageId, dirInfo);
      }
    }
    UnderFileSystem dirFs = getDirUFS(storageId);
    String userTempFolder = CommonUtils.concat(dirInfo.getDirPath(), mUserTempFolder);
    boolean ret = false;

    if (dirFs.exists(userTempFolder)) {
      if (dirFs.isFile(userTempFolder)) {
        ret = dirFs.mkdirs(userTempFolder, true);
      } else {
        ret = true;
      }
    } else {
      ret = dirFs.mkdirs(userTempFolder, true);
    }
    if (ret) {
      return userTempFolder;
    } else {
      return null;
    }
  }

  /**
   * Create a user UnderFileSystem temporary folder and return it
   * 
   * @return the UnderFileSystem temporary folder
   * @throws IOException
   */
  synchronized String createAndGetUserUnderfsTempFolder() throws IOException {
    connect();

    if (mUserUnderfsTempFolder == null) {
      return null;
    }

    if (mUnderFileSystem == null) {
      mUnderFileSystem = UnderFileSystem.get(mUserUnderfsTempFolder);
    }

    mUnderFileSystem.mkdirs(mUserUnderfsTempFolder, true);

    return mUserUnderfsTempFolder;
  }

  /**
   * Create a Dependency
   * 
   * @param parents
   *          the dependency's input files
   * @param children
   *          the dependency's output files
   * @param commandPrefix
   * @param data
   * @param comment
   * @param framework
   * @param frameworkVersion
   * @param dependencyType
   *          the dependency's type, Wide or Narrow
   * @param childrenBlockSizeByte
   *          the block size of the dependency's output files
   * @return the dependency's id
   * @throws IOException
   */
  public synchronized int createDependency(List<String> parents, List<String> children,
      String commandPrefix, List<ByteBuffer> data, String comment, String framework,
      String frameworkVersion, int dependencyType, long childrenBlockSizeByte) throws IOException {
    connect();
    try {
      return mMasterClient.user_createDependency(parents, children, commandPrefix, data, comment,
          framework, frameworkVersion, dependencyType, childrenBlockSizeByte);
    } catch (TException e) {
      mConnected = false;
      throw new IOException(e);
    }
  }

  /**
   * Create a file with the default block size (1GB) in the system. It also creates necessary
   * folders along the path. // TODO It should not create necessary path.
   * 
   * @param path
   *          the path of the file
   * @return The unique file id. It returns -1 if the creation failed.
   * @throws IOException
   *           If file already exists, or path is invalid.
   */
  public synchronized int createFile(String path) throws IOException {
    return createFile(path, UserConf.get().DEFAULT_BLOCK_SIZE_BYTE);
  }

  /**
   * Create a file in the system. It also creates necessary folders along the path.
   * // TODO It should not create necessary path.
   * 
   * @param path
   *          the path of the file
   * @param blockSizeByte
   *          the block size of the file
   * @return The unique file id. It returns -1 if the creation failed.
   * @throws IOException
   *           If file already exists, or path is invalid.
   */
  public synchronized int createFile(String path, long blockSizeByte) throws IOException {
    if (blockSizeByte > (long) Constants.GB * 2) {
      throw new IOException("Block size must be less than 2GB: " + blockSizeByte);
    }

    connect();
    if (!mConnected) {
      return -1;
    }
    String cleanedPath = cleanPathIOException(path);
    int fid = -1;
    try {
      fid = mMasterClient.user_createFile(cleanedPath, blockSizeByte);
    } catch (TException e) {
      mConnected = false;
      throw new IOException(e);
    }
    return fid;
  }

  /**
   * Create a file in the system with a pre-defined underfsPath. It also creates necessary
   * folders along the path. // TODO It should not create necessary path.
   * 
   * @param path
   *          the path of the file in Tachyon
   * @param underfsPath
   *          the path of the file in the underfs
   * @return The unique file id. It returns -1 if the creation failed.
   * @throws IOException
   *           If file already exists, or path is invalid.
   */
  public synchronized int createFile(String path, String underfsPath) throws IOException {
    connect();
    if (!mConnected) {
      return -1;
    }
    String cleanedPath = cleanPathIOException(path);
    try {
      return mMasterClient.user_createFileOnCheckpoint(cleanedPath, underfsPath);
    } catch (TException e) {
      mConnected = false;
      throw new IOException(e);
    }
  }

  /**
   * Create a RawTable and return its id
   * 
   * @param path
   *          the RawTable's path
   * @param columns
   *          number of columns it has
   * @return the id if succeed, -1 otherwise
   * @throws IOException
   */
  public synchronized int createRawTable(String path, int columns) throws IOException {
    return createRawTable(path, columns, ByteBuffer.allocate(0));
  }

  /**
   * Create a RawTable and return its id
   * 
   * @param path
   *          the RawTable's path
   * @param columns
   *          number of columns it has
   * @param metadata
   *          the meta data of the RawTable
   * @return the id if succeed, -1 otherwise
   * @throws IOException
   */
  public synchronized int createRawTable(String path, int columns, ByteBuffer metadata)
      throws IOException {
    connect();
    if (!mConnected) {
      return -1;
    }
    String cleanedPath = cleanPathIOException(path);
    if (columns < 1 || columns > CommonConf.get().MAX_COLUMNS) {
      throw new IOException("Column count " + columns + " is smaller than 1 or " + "bigger than "
          + CommonConf.get().MAX_COLUMNS);
    }

    try {
      return mMasterClient.user_createRawTable(cleanedPath, columns, metadata);
    } catch (TException e) {
      LOG.error(e.getMessage());
      mConnected = false;
      return -1;
    }
  }

  /**
   * Delete the file denoted by the file id.
   * 
   * @param fid
   *          file id
   * @param recursive
   *          if delete the path recursively.
   * @return true if deletion succeed (including the case the file does not exist in the first
   *         place), false otherwise.
   * @throws IOException
   */
  public synchronized boolean delete(int fid, boolean recursive) throws IOException {
    connect();
    if (!mConnected) {
      return false;
    }

    try {
      return mMasterClient.user_delete(fid, recursive);
    } catch (TException e) {
      mConnected = false;
      throw new IOException(e);
    }
  }

  /**
   * Delete the file denoted by the path.
   * 
   * @param path
   *          the file path
   * @param recursive
   *          if delete the path recursively.
   * @return true if the deletion succeed (including the case that the path does not exist in the
   *         first place), false otherwise.
   * @throws IOException
   */
  public synchronized boolean delete(String path, boolean recursive) throws IOException {
    connect();
    if (!mConnected) {
      return false;
    }

    try {
      return mMasterClient.user_delete(path, recursive);
    } catch (TException e) {
      throw new IOException(e);
    }
  }

  /**
   * Return whether the file exists or not
   * 
   * @param path
   *          the file's path in Tachyon file system
   * @return true if it exists, false otherwise
   * @throws IOException
   */
  public synchronized boolean exist(String path) throws IOException {
    return getFileId(path) != -1;
  }

  private synchronized ClientFileInfo fetchClientFileInfo(int fid) throws IOException {
    connect();
    if (!mConnected) {
      return null;
    }
    ClientFileInfo ret = null;
    try {
      ret = mMasterClient.getClientFileInfoById(fid);
    } catch (IOException e) {
      LOG.info(e.getMessage() + fid);
      return null;
    } catch (TException e) {
      LOG.error(e.getMessage());
      mConnected = false;
      return null;
    }

    return ret;
  }

  /**
   * Returns the file info for the block if that file exists on the worker. This is
   * an alpha power-api feature for applications that want short-circuit-read files directly. There
   * is no guarantee that the file still exists after this call returns, as Tachyon may evict blocks
   * from memory at any time.
   * 
   * @param blockId
   *          The id of the block.
   * @return file info on local worker. null if not found
   */
  public WorkerFileInfo getBlockFileInfo(long blockId) throws IOException {
    return getBlockFileInfo(blockId, StorageId.unknownValue());
  }

  /**
   * Returns the file info for the block if that file exists on the worker. This is
   * an alpha power-api feature for applications that want short-circuit-read files directly. There
   * is no guarantee that the file still exists after this call returns, as Tachyon may evict blocks
   * from memory at any time.
   * 
   * @param blockId
   *          The id of the block.
   * @param storageId
   *          storage id of the block
   * @return file info on local worker, null if not found.
   */
  public WorkerFileInfo getBlockFileInfo(long blockId, long storageId) throws IOException {
    if (!hasLocalWorker()) {
      return null;
    }
    try {
      return mWorkerClient.getBlockFileInfo(blockId, storageId);
    } catch (FileDoesNotExistException e) {
      return null;
    } catch (TException e) {
      mConnected = false;
      throw new IOException(e);
    }
  }

  /**
   * Get the block id by the file id and block index. it will check whether the file and the block
   * exist.
   * 
   * @param fId
   *          the file id
   * @param blockIndex
   *          The index of the block in the file.
   * @return the block id if exists
   * @throws IOException
   *           if the file does not exist, or connection issue.
   */
  public synchronized long getBlockId(int fId, int blockIndex) throws IOException {
    ClientFileInfo info = mClientFileInfos.get(fId);
    if (info == null) {
      info = fetchClientFileInfo(fId);
      if (info != null) {
        mClientFileInfos.put(fId, info);
      } else {
        throw new IOException("File " + fId + " does not exist.");
      }
    }

    if (info.blockIds.size() > blockIndex) {
      return info.blockIds.get(blockIndex);
    }

    connect();
    try {
      return mMasterClient.user_getBlockId(fId, blockIndex);
    } catch (TException e) {
      throw new IOException(e);
    }
  }

  /**
   * Get the block id by the file id and offset. it will check whether the file and the block exist.
   * 
   * @param fId
   *          the file id
   * @param offset
   *          The offset of the file.
   * @return the block id if exists
   * @throws IOException
   */
  synchronized long getBlockIdBasedOnOffset(int fId, long offset) throws IOException {
    ClientFileInfo info;
    if (!mClientFileInfos.containsKey(fId)) {
      info = fetchClientFileInfo(fId);
      mClientFileInfos.put(fId, info);
    }
    info = mClientFileInfos.get(fId);

    int index = (int) (offset / info.getBlockSizeByte());

    return getBlockId(fId, index);
  }

  /**
   * @return a new block lock id
   */
  public int getBlockLockId() {
    return mBlockLockId.getAndIncrement();
  }

  /**
   * @param fId
   *          the file id
   * @return the block size of the file, in bytes
   */
  public synchronized long getBlockSizeByte(int fId) {
    return mClientFileInfos.get(fId).getBlockSizeByte();
  }

  /**
   * Get a ClientBlockInfo by the file id and block index
   * 
   * @param fId
   *          the file id
   * @param blockIndex
   *          The index of the block in the file.
   * @return the ClientBlockInfo of the specified block
   * @throws IOException
   */
  public synchronized ClientBlockInfo getClientBlockInfo(int fId, int blockIndex)
      throws IOException {
    boolean fetch = false;
    if (!mClientFileInfos.containsKey(fId)) {
      fetch = true;
    }
    ClientFileInfo info = null;
    if (!fetch) {
      info = mClientFileInfos.get(fId);
      if (info.isFolder || info.blockIds.size() <= blockIndex) {
        fetch = true;
      }
    }

    if (fetch) {
      connect();
      info = fetchClientFileInfo(fId);
      mClientFileInfos.put(fId, info);
    }

    if (info == null) {
      throw new IOException("File " + fId + " does not exist.");
    }
    if (info.isFolder) {
      throw new IOException(new FileDoesNotExistException("File " + fId + " is a folder."));
    }
    if (info.blockIds.size() <= blockIndex) {
      throw new IOException("BlockIndex " + blockIndex + " is out of the bound in file " + info);
    }

    try {
      return mMasterClient.user_getClientBlockInfo(info.blockIds.get(blockIndex));
    } catch (FileDoesNotExistException e) {
      throw new IOException(e);
    } catch (BlockInfoException e) {
      throw new IOException(e);
    } catch (TException e) {
      throw new IOException(e);
    }
  }

  /**
   * Get a ClientDependencyInfo by the dependency id
   * 
   * @param did
   *          the dependency id
   * @return the ClientDependencyInfo of the specified dependency
   * @throws IOException
   */
  public synchronized ClientDependencyInfo getClientDependencyInfo(int did) throws IOException {
    connect();
    try {
      return mMasterClient.getClientDependencyInfo(did);
    } catch (TException e) {
      mConnected = false;
      throw new IOException(e);
    }
  }

  /**
   * Get a ClientFileInfo of the file
   * 
   * @param path
   *          the file path in Tachyon file system
   * @param useCachedMetadata
   *          if true use the local cached meta data
   * @return the ClientFileInfo
   * @throws IOException
   */
  private synchronized ClientFileInfo getClientFileInfo(String path, boolean useCachedMetadata)
      throws IOException {
    connect();
    if (!mConnected) {
      return null;
    }
    ClientFileInfo ret;
    String cleanedPath = cleanPathIOException(path);
    if (useCachedMetadata && mCachedClientFileInfos.containsKey(cleanedPath)) {
      return mCachedClientFileInfos.get(path);
    }
    try {
      ret = mMasterClient.user_getClientFileInfoByPath(cleanedPath);
    } catch (IOException e) {
      LOG.info(e.getMessage() + cleanedPath);
      return null;
    } catch (TException e) {
      LOG.error(e.getMessage());
      mConnected = false;
      return null;
    }

    // TODO LRU on this Map.
    if (ret != null && useCachedMetadata) {
      mCachedClientFileInfos.put(cleanedPath, ret);
    } else {
      mCachedClientFileInfos.remove(cleanedPath);
    }

    return ret;
  }

  /**
   * Get the creation time of the file
   * 
   * @param fId
   *          the file id
   * @return the creation time in milliseconds
   */
  public synchronized long getCreationTimeMs(int fId) {
    return mClientFileInfos.get(fId).getCreationTimeMs();
  }

  /**
   * get UnderFileSystem of a specified DIR
   * 
   * @param storageId
   *          the storage id of the Dir
   * @return the UnderFileSystem of the Dir
   * @throws IOException
   */
  private UnderFileSystem getDirUFS(long storageId) throws IOException {
    if (mIdToUFS.containsKey(storageId)) {
      return mIdToUFS.get(storageId);
    } else {
      return null;
    }
  }

  /**
   * Get <code>TachyonFile</code> based on the file id.
   * 
   * NOTE: This *will* use cached file metadata, and so will not see changes to dynamic properties,
   * such as the pinned flag. This is also different from the behavior of getFile(path), which
   * by default will not use cached metadata.
   * 
   * @param fid
   *          file id.
   * @return TachyonFile of the file id, or null if the file does not exist.
   */
  public synchronized TachyonFile getFile(int fid) throws IOException {
    return getFile(fid, true);
  }

  /**
   * Get <code>TachyonFile</code> based on the file id. If useCachedMetadata, this will not see
   * changes to the file's pin setting, or other dynamic properties.
   * 
   * @return TachyonFile of the file id, or null if the file does not exist.
   */
  public synchronized TachyonFile getFile(int fid, boolean useCachedMetadata) throws IOException {
    if (!useCachedMetadata || !mClientFileInfos.containsKey(fid)) {
      ClientFileInfo clientFileInfo = fetchClientFileInfo(fid);
      if (clientFileInfo == null) {
        return null;
      }
      mClientFileInfos.put(fid, clientFileInfo);
    }
    return new TachyonFile(this, fid);
  }

  /**
   * Get <code>TachyonFile</code> based on the path. Does not utilize the file metadata cache.
   * 
   * @param path
   *          file path.
   * @return TachyonFile of the path, or null if the file does not exist.
   * @throws IOException
   */
  public synchronized TachyonFile getFile(String path) throws IOException {
    return getFile(path, false);
  }

  /**
   * Get <code>TachyonFile</code> based on the path. If useCachedMetadata, this will not see
   * changes to the file's pin setting, or other dynamic properties.
   */
  public synchronized TachyonFile getFile(String path, boolean useCachedMetadata)
      throws IOException {
    String cleanedPath = cleanPathIOException(path);
    ClientFileInfo clientFileInfo = getClientFileInfo(cleanedPath, useCachedMetadata);
    if (clientFileInfo == null) {
      return null;
    }
    mClientFileInfos.put(clientFileInfo.getId(), clientFileInfo);
    return new TachyonFile(this, clientFileInfo.getId());
  }

  /**
   * Get all the blocks' ids of the file
   * 
   * @param fId
   *          the file id
   * @return the list of blocks' ids
   * @throws IOException
   */
  public synchronized List<Long> getFileBlockIdList(int fId) throws IOException {
    connect();

    ClientFileInfo info = mClientFileInfos.get(fId);
    if (info == null || !info.isComplete) {
      info = fetchClientFileInfo(fId);
      mClientFileInfos.put(fId, info);
    }

    if (info == null) {
      throw new IOException("File " + fId + " does not exist.");
    }

    return info.blockIds;
  }

  /**
   * Get all the blocks' info of the file
   * 
   * @param fid
   *          the file id
   * @return the list of the blocks' info
   * @throws IOException
   */
  public synchronized List<ClientBlockInfo> getFileBlocks(int fid) throws IOException {
    // TODO Should read from mClientFileInfos if possible. Should add timeout to improve this.
    connect();
    if (!mConnected) {
      return null;
    }

    try {
      return mMasterClient.user_getFileBlocks(fid);
    } catch (TException e) {
      mConnected = false;
      throw new IOException(e);
    }
  }

  /**
   * Get the net address of all the file's hosts
   * 
   * @param fileId
   *          the file id
   * @return the list of all the file's hosts, in String
   * @throws IOException
   */
  public synchronized List<String> getFileHosts(int fileId) throws IOException {
    connect();
    if (!mConnected) {
      return null;
    }

    List<NetAddress> adresses = getFileNetAddresses(fileId);
    List<String> ret = new ArrayList<String>(adresses.size());
    for (NetAddress address : adresses) {
      ret.add(address.mHost);
      if (address.mHost.endsWith(".ec2.internal")) {
        ret.add(address.mHost.substring(0, address.mHost.length() - 13));
      }
    }

    return ret;
  }

  /**
   * Get file id by the path. It will check if the path exists.
   * 
   * @param path
   *          the path in Tachyon file system
   * @return the file id if exists, -1 otherwise
   * @throws IOException
   */
  public synchronized int getFileId(String path) throws IOException {
    connect();
    if (!mConnected) {
      return -1;
    }
    int fid = -1;
    String cleanedPath = cleanPathIOException(path);
    try {
      fid = mMasterClient.user_getFileId(cleanedPath);
    } catch (TException e) {
      LOG.error(e.getMessage());
      mConnected = false;
      return -1;
    }
    return fid;
  }

  /**
   * @param fid
   *          the file id
   * @return the current length of the file
   * @throws IOException
   */
  synchronized long getFileLength(int fid) throws IOException {
    if (!mClientFileInfos.get(fid).isComplete) {
      ClientFileInfo info = fetchClientFileInfo(fid);
      mClientFileInfos.put(fid, info);
    }
    return mClientFileInfos.get(fid).getLength();
  }

  public synchronized List<NetAddress> getFileNetAddresses(int fileId) throws IOException {
    connect();
    if (!mConnected) {
      return null;
    }

    List<NetAddress> ret = new ArrayList<NetAddress>();
    try {
      List<ClientBlockInfo> blocks = mMasterClient.user_getFileBlocks(fileId);
      Set<NetAddress> locationSet = new HashSet<NetAddress>();
      for (ClientBlockInfo info : blocks) {
        locationSet.addAll(info.getLocations());
      }
      ret.addAll(locationSet);
    } catch (TException e) {
      mConnected = false;
      throw new IOException(e);
    }
    return ret;
  }

  public synchronized List<List<String>> getFilesHosts(List<Integer> fileIds) throws IOException {
    List<List<String>> ret = new ArrayList<List<String>>();
    for (int k = 0; k < fileIds.size(); k ++) {
      ret.add(getFileHosts(fileIds.get(k)));
    }

    return ret;
  }

  public synchronized List<List<NetAddress>> getFilesNetAddresses(List<Integer> fileIds)
      throws IOException {
    List<List<NetAddress>> ret = new ArrayList<List<NetAddress>>();
    for (int k = 0; k < fileIds.size(); k ++) {
      ret.add(getFileNetAddresses(fileIds.get(k)));
    }

    return ret;
  }

  /**
   * Get the next new block's id of the file.
   * 
   * @param fId
   *          the file id
   * @return the id of the next block
   * @throws IOException
   */
  private synchronized long getNextBlockId(int fId) throws IOException {
    connect();
    try {
      return mMasterClient.user_createNewBlock(fId);
    } catch (TException e) {
      mConnected = false;
      throw new IOException(e);
    }
  }

  /**
   * Get the number of blocks of a file. Only works when the file exists.
   * 
   * @param fId
   *          the file id
   * @return the number of blocks if the file exists
   * @throws IOException
   */
  synchronized int getNumberOfBlocks(int fId) throws IOException {
    ClientFileInfo info = mClientFileInfos.get(fId);
    if (info == null || !info.isComplete) {
      info = fetchClientFileInfo(fId);
      mClientFileInfos.put(fId, info);
    }
    if (info == null) {
      throw new IOException("File " + fId + " does not exist.");
    }
    return info.getBlockIds().size();
  }

  /**
   * Get the number of the files under the folder. Return 1 if the path is a file. Return the
   * number of direct children if the path is a folder.
   * 
   * @param path
   *          the path in Tachyon file system
   * @return the number of the files
   * @throws IOException
   */
  public synchronized int getNumberOfFiles(String path) throws IOException {
    connect();
    try {
      return mMasterClient.user_getNumberOfFiles(path);
    } catch (TException e) {
      mConnected = false;
      throw new IOException(e);
    }
  }

  /**
   * @param fid
   *          the file id
   * @return the path of the file in Tachyon file system
   */
  synchronized String getPath(int fid) {
    return mClientFileInfos.get(fid).getPath();
  }

  /**
   * Get the RawTable by id
   * 
   * @param id
   *          the id of the raw table
   * @return the RawTable
   * @throws IOException
   */
  public synchronized RawTable getRawTable(int id) throws IOException {
    connect();
    ClientRawTableInfo clientRawTableInfo = null;
    try {
      clientRawTableInfo = mMasterClient.user_getClientRawTableInfoById(id);
    } catch (TException e) {
      mConnected = false;
      throw new IOException(e);
    }
    return new RawTable(this, clientRawTableInfo);
  }

  /**
   * Get the RawTable by path
   * 
   * @param path
   *          the path of the raw table
   * @return the RawTable
   * @throws IOException
   */
  public synchronized RawTable getRawTable(String path) throws IOException {
    connect();
    String cleanedPath = cleanPathIOException(path);
    ClientRawTableInfo clientRawTableInfo;
    try {
      clientRawTableInfo = mMasterClient.user_getClientRawTableInfoByPath(cleanedPath);
    } catch (TException e) {
      mConnected = false;
      throw new IOException(e);
    }
    return new RawTable(this, clientRawTableInfo);
  }

  /**
   * @return the local root data folder
   * @throws IOException
   */
  public synchronized String getRootFolder() throws IOException {
    connect();
    return mLocalDataFolder;
  }

  public synchronized long getSpaceLocally(long requestSpaceBytes) throws IOException {
    long storageId = StorageId.unknownValue();
    for (Entry<Long, Long> entry : mAvailableSpaceBytes.entrySet()) {
      if (entry.getValue() >= requestSpaceBytes) {
        storageId = entry.getKey();
        mAvailableSpaceBytes.put(storageId, entry.getValue() - requestSpaceBytes);
        break;
      }
    }
    return storageId;
  }

  /**
   * Get storage id of the block
   * 
   * @param blockId
   *          the id of the block
   * @return the storage id of the block
   * @throws IOException
   */
  public synchronized long getStorageIdByBlockId(long blockId) throws IOException {
    connect();
    long storageId = StorageId.unknownValue();
    try {
      storageId = mWorkerClient.getStorageIdByBlockId(blockId);
    } catch (FileDoesNotExistException e) {
      return storageId;
    } catch (TException e) {
      throw new IOException(e);
    }
    return storageId;
  }

  /**
   * Return the under file system path of the file
   * 
   * @param fid
   *          the file id
   * @return the checkpoint path of the file
   * @throws IOException
   */
  synchronized String getUfsPath(int fid) throws IOException {
    ClientFileInfo info = mClientFileInfos.get(fid);
    if (info == null || !info.getUfsPath().equals("")) {
      info = fetchClientFileInfo(fid);
      mClientFileInfos.put(fid, info);
    }

    return info.getUfsPath();
  }

  /**
   * @return the address of the UnderFileSystem
   * @throws IOException
   */
  public synchronized String getUnderfsAddress() throws IOException {
    connect();
    try {
      return mMasterClient.user_getUnderfsAddress();
    } catch (TException e) {
      throw new IOException(e.getMessage());
    }
  }

  /**
   * @return all the works' info
   * @throws IOException
   */
  public synchronized List<ClientWorkerInfo> getWorkersInfo() throws IOException {
    connect();
    try {
      return mMasterClient.getWorkersInfo();
    } catch (TException e) {
      mConnected = false;
      throw new IOException(e);
    }
  }

  /**
   * @return true if there is a local worker, false otherwise
   * @throws IOException
   */
  public synchronized boolean hasLocalWorker() throws IOException {
    connect();
    return (mIsWorkerLocal && mWorkerClient != null);
  }

  private void initalizeDirUFS() throws IOException {
    WorkerDirInfo dirInfo;
    for (Entry<Long, WorkerDirInfo> idToDir : mIdToDirInfo.entrySet()) {
      long storageId = idToDir.getKey();
      dirInfo = idToDir.getValue();
      UnderFileSystem ufs;
      try {
        ufs =
            UnderFileSystem.get(dirInfo.getDirPath(),
                CommonUtils.byteArrayToObject(dirInfo.getConf()));
      } catch (ClassNotFoundException e) {
        throw new IOException(e.getMessage());
      }
      mIdToUFS.put(storageId, ufs);
    }
    return;
  }

  /**
   * @param fid
   *          the file id
   * @return true if this file is complete, false otherwise
   * @throws IOException
   */
  synchronized boolean isComplete(int fid) throws IOException {
    if (!mClientFileInfos.get(fid).isComplete) {
      mClientFileInfos.put(fid, fetchClientFileInfo(fid));
    }
    return mClientFileInfos.get(fid).isComplete;
  }

  /**
   * @return true if this client is connected to master, false otherwise
   */
  public synchronized boolean isConnected() {
    return mConnected;
  }

  /**
   * @param fid
   *          the file id
   * @return true if the file is a directory, false otherwise
   */
  synchronized boolean isDirectory(int fid) {
    return mClientFileInfos.get(fid).isFolder;
  }

  /**
   * Return whether the file is in memory or not. Note that a file may be partly in memory. This
   * value is true only if the file is fully in memory.
   * 
   * @param fid
   *          the file id
   * @return true if the file is fully in memory, false otherwise
   * @throws IOException
   */
  synchronized boolean isInMemory(int fid) throws IOException {
    ClientFileInfo info = fetchClientFileInfo(fid);
    mClientFileInfos.put(info.getId(), info);
    return 100 == info.inMemoryPercentage;
  }

  /**
   * @param fid
   *          the file id
   * @return true if the file is need pin, false otherwise
   */
  synchronized boolean isNeedPin(int fid) {
    return mClientFileInfos.get(fid).isPinned;
  }

  /** Returns true if the given file or folder has its "pinned" flag set. */
  public synchronized boolean isPinned(int fid, boolean useCachedMetadata) throws IOException {
    ClientFileInfo info;
    if (!useCachedMetadata || !mClientFileInfos.containsKey(fid)) {
      info = fetchClientFileInfo(fid);
      mClientFileInfos.put(fid, info);
    }
    info = mClientFileInfos.get(fid);

    return info.isPinned;
  }

  /**
   * List the files under the given path. List the files recursively if <code>recursive</code> is
   * true
   * 
   * @param path
   *          the path in Tachyon file system
   * @param recursive
   *          if true list the files recursively
   * @return the list of the files' id
   * @throws IOException
   */
  public synchronized List<Integer> listFiles(String path, boolean recursive) throws IOException {
    connect();
    try {
      return mMasterClient.user_listFiles(path, recursive);
    } catch (TException e) {
      mConnected = false;
      throw new IOException(e);
    }
  }

  /**
   * If the <code>path</code> is a directory, return all the direct entries in it. If the
   * <code>path</code> is a file, return its ClientFileInfo.
   * 
   * @param path
   *          the target directory/file path
   * @return A list of ClientFileInfo
   * @throws IOException
   */
  public synchronized List<ClientFileInfo> listStatus(String path) throws IOException {
    connect();
    try {
      return mMasterClient.listStatus(path);
    } catch (TException e) {
      mConnected = false;
      throw new IOException(e);
    }
  }

  /**
   * Lock a block in the current TachyonFS.
   * 
   * @param blockId
   *          The id of the block to lock. <code>blockId</code> must be positive.
   * @param blockLockId
   *          The block lock id of the block of lock. <code>blockLockId</code> must be non-negative.
   * @return true if successfully lock the block, false otherwise (or invalid parameter).
   */
  private synchronized boolean lockBlock(long blockId, int blockLockId) throws IOException {
    if (blockId <= 0 || blockLockId < 0) {
      return false;
    }

    if (mLockedBlockIds.containsKey(blockId)) {
      mLockedBlockIds.get(blockId).add(blockLockId);
      return true;
    }

    connect();
    if (!mConnected || mWorkerClient == null || !mIsWorkerLocal) {
      return false;
    }
    try {
      mWorkerClient.lockBlock(blockId, mUserId);
    } catch (TException e) {
      LOG.error(e.getMessage());
      return false;
    }
    Set<Integer> lockIds = new HashSet<Integer>(4);
    lockIds.add(blockLockId);
    mLockedBlockIds.put(blockId, lockIds);
    return true;
  }

  /**
   * Return a list of files/directories under the given path.
   * 
   * @param path
   *          the path in the TFS.
   * @param recursive
   *          whether or not to list files/directories under path recursively.
   * @return a list of files/directories under path if recursive is false, or files/directories
   *         under its subdirectories (sub-subdirectories, and so forth) if recursive is true, or
   *         null
   *         if the content of path is empty, i.e., no files found under path.
   * @throws IOException
   *           if some TException is thrown when trying to read content of path.
   */
  public synchronized List<String> ls(String path, boolean recursive) throws IOException {
    connect();
    try {
      return mMasterClient.user_ls(path, recursive);
    } catch (FileDoesNotExistException e) {
      mConnected = false;
      return null;
    } catch (TException e) {
      mConnected = false;
      throw new IOException(e);
    }
  }

  /**
   * Create a directory if it does not exist. The method also creates necessary non-existing
   * parent folders.
   * 
   * @param path
   *          Directory path.
   * @return true if the folder is created successfully or already existing. false otherwise.
   * @throws IOException
   */
  public synchronized boolean mkdir(String path) throws IOException {
    connect();
    if (!mConnected) {
      return false;
    }
    String cleanedPath = cleanPathIOException(path);
    try {
      return mMasterClient.user_mkdir(cleanedPath);
    } catch (TException e) {
      throw new IOException(e);
    }
  }

  /**
   * Tell master that out of memory when pin file
   * 
   * @param fid
   *          the file id
   * @throws IOException
   */
  public synchronized void outOfMemoryForPinFile(int fid) throws IOException {
    connect();
    if (mConnected) {
      try {
        mMasterClient.user_outOfMemoryForPinFile(fid);
      } catch (TException e) {
        LOG.error(e.getMessage());
      }
    }
  }

  /** Alias for setPinned(fid, true). */
  public synchronized void pinFile(int fid) throws IOException {
    setPinned(fid, true);
  }

  /**
   * promte block file back to the top storage tier, after access it
   * 
   * @param blockId
   *          id of the block
   * @return true if promote success, false otherwise
   * @throws IOException
   */
  public boolean promoteBlock(long blockId) throws IOException {
    connect();
    if (mWorkerClient == null || !mIsWorkerLocal) {
      return false;
    }
    try {
      return mWorkerClient.promoteBlock(mUserId, blockId);
    } catch (TException e) {
      LOG.error(e.getMessage());
      return false;
    }
  }

  /**
   * Read local block return a TachyonByteBuffer
   * 
   * @param blockId
   *          The id of the block.
   * @param storageId
   *          storage id of the block.
   * @param offset
   *          The start position to read.
   * @param len
   *          The length to read. -1 represents read the whole block.
   * @param ufsConf
   *          The configuration of UnderFileSystem.
   * @return <code>TachyonByteBuffer</code> containing the block.
   * @throws IOException
   */
  public TachyonByteBuffer readLocalByteBuffer(long blockId, long storageId, long offset,
      long len, Object ufsConf) throws IOException {
    if (offset < 0) {
      throw new IOException("Offset can not be negative: " + offset);
    }
    if (len < 0 && len != -1) {
      throw new IOException("Length can not be negative except -1: " + len);
    }

    int blockLockId = getBlockLockId();
    if (!lockBlock(blockId, blockLockId)) {
      return null;
    }
    WorkerFileInfo fileInfo = getBlockFileInfo(blockId, storageId);
    if (fileInfo != null) {
      try {
        String filePath = fileInfo.getFilePath();
        long fileLength = fileInfo.getFileSize();
        if (fileLength == -1) {
          throw new FileNotFoundException("Block file not found, blockId:" + blockId);
        }
        String error = null;
        if (offset > fileLength) {
          error = String.format("Offset(%d) is larger than file length(%d)", offset, fileLength);
        }
        if (error == null && len != -1 && offset + len > fileLength) {
          error =
              String.format("Offset(%d) plus length(%d) is larger than file length(%d)", offset,
                  len, fileLength);
        }
        if (error != null) {
          throw new IOException(error);
        }

        if (len == -1) {
          len = fileLength - offset;
        }
        BlockHandler handler = BlockHandler.get(filePath);
        ByteBuffer buf = handler.read((int) offset, (int) len);
        handler.close();
        accessLocalBlock(blockId);
        return new TachyonByteBuffer(this, buf, blockId, blockLockId);
      } catch (FileNotFoundException e) {
        LOG.info(blockId + " is not found in storage dir:" + storageId);
      } catch (IllegalArgumentException e) {
        LOG.info(e.getMessage());
      }
    }

    unlockBlock(blockId, blockLockId);
    return null;
  }

  /**
   * Read local block return a TachyonByteBuffer
   * 
   * @param blockId
   *          The id of the block.
   * @param offset
   *          The start position to read.
   * @param len
   *          The length to read. -1 represents read the whole block.
   * @param ufsConf
   *          The configuration of UnderFileSystem.
   * @return <code>TachyonByteBuffer</code> containing the block.
   * @throws IOException
   */
  public TachyonByteBuffer
      readLocalByteBuffer(long blockId, long offset, long len, Object ufsConf) throws IOException {
    return readLocalByteBuffer(blockId, StorageId.unknownValue(), offset, len, ufsConf);
  }

  /**
   * Read the whole local block.
   * 
   * @param blockId
   *          The id of the block to read.
   * @return <code>TachyonByteBuffer</code> containing the whole block.
   * @throws IOException
   */
  TachyonByteBuffer readLocalByteBuffer(long blockId, Object ufsConf) throws IOException {
    return readLocalByteBuffer(blockId, 0, -1, ufsConf);
  }

  /**
   * Rename the file
   * 
   * @param storageId
   *          the storage id of the Dir
   * @param releaseSpaceBytes
   *          the size of the space released
   */
  public synchronized void releaseSpace(long storageId, long releaseSpaceBytes) {
    if (mAvailableSpaceBytes.containsKey(storageId)) {
      long curAvaliableSpaceBytes = mAvailableSpaceBytes.get(storageId);
      mAvailableSpaceBytes.put(storageId, curAvaliableSpaceBytes + releaseSpaceBytes);
    } else {
      mAvailableSpaceBytes.put(storageId, releaseSpaceBytes);
    }
  }

  /**
   * Rename the file
   * 
   * @param fId
   *          the file id
   * @param path
   *          the new path of the file in Tachyon file system
   * @return true if succeed, false otherwise
   * @throws IOException
   */
  public synchronized boolean rename(int fId, String path) throws IOException {
    connect();
    if (!mConnected) {
      return false;
    }

    try {
      mMasterClient.user_renameTo(fId, path);
    } catch (TException e) {
      LOG.error(e.getMessage());
      return false;
    }

    return true;
  }

  /**
   * Rename the srcPath to the dstPath
   * 
   * @param srcPath
   * @param dstPath
   * @return true if succeed, false otherwise.
   * @throws IOException
   */
  public synchronized boolean rename(String srcPath, String dstPath) throws IOException {
    connect();
    if (!mConnected) {
      return false;
    }

    try {
      if (srcPath.equals(dstPath) && exist(srcPath)) {
        return true;
      }

      return mMasterClient.user_rename(srcPath, dstPath);
    } catch (TException e) {
      LOG.error(e.getMessage());
      return false;
    }
  }

  /**
   * Report the lost file to master
   * 
   * @param fileId
   *          the lost file id
   * @throws IOException
   */
  public synchronized void reportLostFile(int fileId) throws IOException {
    connect();
    try {
      mMasterClient.user_reportLostFile(fileId);
    } catch (TException e) {
      mConnected = false;
      throw new IOException(e);
    }
  }

  /**
   * Request the dependency's needed files
   * 
   * @param depId
   *          the dependency id
   * @throws IOException
   */
  public synchronized void requestFilesInDependency(int depId) throws IOException {
    connect();
    try {
      mMasterClient.user_requestFilesInDependency(depId);
    } catch (TException e) {
      mConnected = false;
      throw new IOException(e);
    }
  }

  /**
   * Try to request space from worker. Only works when a local worker exists.
   * 
   * @param requestSpaceBytes
   *          the space size in bytes
   * @return true if succeed, false otherwise
   * @throws IOException
   */
  public synchronized long requestSpace(long requestSpaceBytes) throws IOException {
    if (!hasLocalWorker()) {
      return StorageId.unknownValue();
    }
    long storageId = getSpaceLocally(requestSpaceBytes);
    if (storageId != StorageId.unknownValue()) {
      return storageId;
    } else {
      long toRequestSpaceBytes = Math.max(requestSpaceBytes, USER_QUOTA_UNIT_BYTES);
      try {
        storageId = mWorkerClient.requestSpace(mUserId, toRequestSpaceBytes);
      } catch (TachyonException e) {
        LOG.error(e.getMessage());
        return storageId;
      } catch (TException e) {
        LOG.error(e.getMessage());
        mWorkerClient = null;
        return storageId;
      }
      long availableSpace = toRequestSpaceBytes;
      if (mAvailableSpaceBytes.containsKey(storageId)) {
        availableSpace = toRequestSpaceBytes + mAvailableSpaceBytes.get(storageId);
      }
      if (availableSpace >= requestSpaceBytes) {
        mAvailableSpaceBytes.put(storageId, availableSpace - requestSpaceBytes);
        return storageId;
      } else {
        return StorageId.unknownValue();
      }
    }
  }

  /**
   * Sets the "pinned" flag for the given file. Pinned files are never evicted
   * by Tachyon until they are unpinned.
   * 
   * Calling setPinned() on a folder will recursively set the "pinned" flag on
   * all of that folder's children. This may be an expensive operation for
   * folders with many files/subfolders.
   */
  public synchronized void setPinned(int fid, boolean pinned) throws IOException {
    connect();
    if (!mConnected) {
      throw new IOException("Could not connect to Tachyon Master");
    }

    try {
      mMasterClient.user_setPinned(fid, pinned);
    } catch (TException e) {
      LOG.error(e.getMessage());
      throw Throwables.propagate(e);
    }
  }

  /**
   * Print out the string representation of this Tachyon server address.
   * 
   * @return the string representation like tachyon://host:port or tachyon-ft://host:port
   */
  @Override
  public String toString() {
    return (mZookeeperMode ? Constants.HEADER_FT : Constants.HEADER) + mMasterAddress.toString();
  }

  /**
   * Unlock a block in the current TachyonFS.
   * 
   * @param blockId
   *          The id of the block to unlock. <code>blockId</code> must be positive.
   * @param blockLockId
   *          The block lock id of the block of unlock. <code>blockLockId</code> must be
   *          non-negative.
   * @return true if successfully unlock the block with <code>blockLockId</code>,
   *         false otherwise (or invalid parameter).
   */
  synchronized boolean unlockBlock(long blockId, int blockLockId) throws IOException {
    if (blockId <= 0 || blockLockId < 0) {
      return false;
    }

    if (!mLockedBlockIds.containsKey(blockId)) {
      return true;
    }
    Set<Integer> lockIds = mLockedBlockIds.get(blockId);
    lockIds.remove(blockLockId);
    if (!lockIds.isEmpty()) {
      return true;
    }

    connect();
    if (!mConnected || mWorkerClient == null || !mIsWorkerLocal) {
      return false;
    }
    try {
      mWorkerClient.unlockBlock(blockId, mUserId);
      mLockedBlockIds.remove(blockId);
    } catch (TException e) {
      LOG.error(e.getMessage());
      return false;
    }
    return true;
  }

  /** Alias for setPinned(fid, false). */
  public synchronized void unpinFile(int fid) throws IOException {
    setPinned(fid, false);
  }

  /**
   * Update the RawTable's meta data
   * 
   * @param id
   *          the raw table's id
   * @param metadata
   *          the new meta data
   * @throws IOException
   */
  public synchronized void updateRawTableMetadata(int id, ByteBuffer metadata) throws IOException {
    connect();
    try {
      mMasterClient.user_updateRawTableMetadata(id, metadata);
    } catch (TException e) {
      mConnected = false;
      throw new IOException(e);
    }
  }
}
