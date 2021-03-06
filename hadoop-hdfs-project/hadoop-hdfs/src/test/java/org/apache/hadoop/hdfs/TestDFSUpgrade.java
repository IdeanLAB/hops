/**
* Licensed to the Apache Software Foundation (ASF) under one
* or more contributor license agreements.  See the NOTICE file
* distributed with this work for additional information
* regarding copyright ownership.  The ASF licenses this file
* to you under the Apache License, Version 2.0 (the
* "License"); you may not use this file except in compliance
* with the License.  You may obtain a copy of the License at
*
*     http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package org.apache.hadoop.hdfs;

import static org.apache.hadoop.hdfs.server.common.HdfsServerConstants.NodeType.DATA_NODE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.util.regex.Pattern;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdfs.client.HdfsClientConfigKeys;
import org.apache.hadoop.hdfs.protocol.HdfsConstants;
import org.apache.hadoop.hdfs.protocol.HdfsConstants.RollingUpgradeAction;
import org.apache.hadoop.hdfs.protocol.HdfsConstants.SafeModeAction;
import org.apache.hadoop.hdfs.server.common.HdfsServerConstants.NodeType;
import org.apache.hadoop.hdfs.server.common.HdfsServerConstants.StartupOption;
import org.apache.hadoop.hdfs.server.common.Storage;
import org.apache.hadoop.hdfs.server.common.StorageInfo;
import org.apache.hadoop.ipc.RemoteException;
import org.apache.hadoop.util.StringUtils;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;


/**
* This test ensures the appropriate response (successful or failure) from
* the system when the system is upgraded under various storage state and
* version conditions.
*/
public class TestDFSUpgrade {
 
  // TODO: Avoid hard-coding expected_txid. The test should be more robust.
  private static final int EXPECTED_TXID = 61;

  private static final Log LOG = LogFactory.getLog(TestDFSUpgrade.class.getName());
  private Configuration conf;
  private int testCounter = 0;
  private MiniDFSCluster cluster = null;
    
  /**
   * Writes an INFO log message containing the parameters.
   */
  void log(String label, int numDirs) {
    LOG.info("============================================================");
    LOG.info("***TEST " + (testCounter++) + "*** " 
             + label + ":"
             + " numDirs="+numDirs);
  }
  
  /**
   * For datanode, for a block pool, verify that the current and previous
   * directories exist. Verify that previous hasn't been modified by comparing
   * the checksum of all its files with their original checksum. It
   * is assumed that the server has recovered and upgraded.
   */
  void checkDataNode(String[] baseDirs, String bpid) throws IOException {
    for (int i = 0; i < baseDirs.length; i++) {
      File current = new File(baseDirs[i], "current/" + bpid + "/current");
      assertEquals(UpgradeUtilities.checksumContents(DATA_NODE, current, false),
        UpgradeUtilities.checksumMasterDataNodeContents());
      
      // block files are placed under <sd>/current/<bpid>/current/finalized
      File currentFinalized = 
        MiniDFSCluster.getFinalizedDir(new File(baseDirs[i]), bpid);
      assertEquals(UpgradeUtilities.checksumContents(DATA_NODE,
          currentFinalized, true),
          UpgradeUtilities.checksumMasterBlockPoolFinalizedContents());
      
      File previous = new File(baseDirs[i], "current/" + bpid + "/previous");
      assertTrue(previous.isDirectory());
      assertEquals(UpgradeUtilities.checksumContents(DATA_NODE, previous, false),
          UpgradeUtilities.checksumMasterDataNodeContents());
      
      File previousFinalized = 
        new File(baseDirs[i], "current/" + bpid + "/previous"+"/finalized");
      assertEquals(UpgradeUtilities.checksumContents(DATA_NODE,
          previousFinalized, true),
          UpgradeUtilities.checksumMasterBlockPoolFinalizedContents());
      
    }
  }

  /**
   * Attempts to start a NameNode with the given operation.  Starting
   * the NameNode should throw an exception.
   */
  void startNameNodeShouldFail(StartupOption operation) {
    startNameNodeShouldFail(operation, null, null);
  }

  /**
   * Attempts to start a NameNode with the given operation.  Starting
   * the NameNode should throw an exception.
   * @param operation - NameNode startup operation
   * @param exceptionClass - if non-null, will check that the caught exception
   *     is assignment-compatible with exceptionClass
   * @param messagePattern - if non-null, will check that a substring of the 
   *     message from the caught exception matches this pattern, via the
   *     {@link Matcher#find()} method.
   */
  void startNameNodeShouldFail(StartupOption operation,
      Class<? extends Exception> exceptionClass, Pattern messagePattern) {
    try {
      cluster = new MiniDFSCluster.Builder(conf).numDataNodes(0)
                                                .startupOption(operation)
                                                .format(false)
                                                .manageDataDfsDirs(false)
                                                .manageNameDfsDirs(false)
                                                .build(); // should fail
      fail("NameNode should have failed to start");
      
    } catch (Exception e) {
      // expect exception
      if (exceptionClass != null) {
        assertTrue("Caught exception is not of expected class "
            + exceptionClass.getSimpleName() + ": "
            + StringUtils.stringifyException(e), 
            exceptionClass.isInstance(e));
      }
      if (messagePattern != null) {
        assertTrue("Caught exception message string does not match expected pattern \""
            + messagePattern.pattern() + "\" : "
            + StringUtils.stringifyException(e), 
            messagePattern.matcher(e.getMessage()).find());
      }
      LOG.info("Successfully detected expected NameNode startup failure.");
    }
  }
  
  /**
   * Attempts to start a DataNode with the given operation. Starting
   * the given block pool should fail.
   * @param operation startup option
   * @param bpid block pool Id that should fail to start
   * @throws IOException 
   */
  void startBlockPoolShouldFail(StartupOption operation, String bpid) throws IOException {
    cluster.startDataNodes(conf, 1, false, operation, null); // should fail
    assertFalse("Block pool " + bpid + " should have failed to start",
        cluster.getDataNodes().get(0).isBPServiceAlive(bpid));
  }
 
  /**
   * Create an instance of a newly configured cluster for testing that does
   * not manage its own directories or files
   */
  private MiniDFSCluster createCluster() throws IOException {
    return new MiniDFSCluster.Builder(conf).numDataNodes(0)
                                           .format(false)
                                           .manageDataDfsDirs(false)
                                           .manageNameDfsDirs(false)
                                           .startupOption(StartupOption.UPGRADE)
                                           .build();
  }
  
  @BeforeClass
  public static void initialize() throws Exception {
    UpgradeUtilities.initialize();
  }
  
  /**
   * This test attempts to upgrade the NameNode and DataNode under
   * a number of valid and invalid conditions.
   */
  @Test(timeout = 600000)
  public void testUpgrade() throws Exception {
    File[] baseDirs;
    StorageInfo storageInfo = null;
    for (int numDirs = 1; numDirs <= 2; numDirs++) {
      conf = new HdfsConfiguration();
      conf = UpgradeUtilities.initializeStorageStateConf(numDirs, conf);
      conf.setLong(DFSConfigKeys.DFS_HEARTBEAT_INTERVAL_KEY, 1);
      conf.setInt(DFSConfigKeys.DFS_NAMENODE_HEARTBEAT_RECHECK_INTERVAL_KEY, 10 * 1000); 
      conf.setInt(DFSConfigKeys.DFS_CLIENT_FAILOVER_MAX_ATTEMPTS_KEY, /*default 15*/ 1);
      conf.set(HdfsClientConfigKeys.Retry.POLICY_SPEC_KEY, "1,1");
      String[] dataNodeDirs = conf.getStrings(DFSConfigKeys.DFS_DATANODE_DATA_DIR_KEY);
      
      log("Normal NameNode upgrade", numDirs);
      cluster = createCluster();

      // make sure that rolling upgrade cannot be started
      try {
        final DistributedFileSystem dfs = cluster.getFileSystem();
        dfs.setSafeMode(SafeModeAction.SAFEMODE_ENTER);
        dfs.rollingUpgrade(RollingUpgradeAction.PREPARE);
        fail();
      } catch(RemoteException re) {
//        assertEquals(InconsistentFSStateException.class.getName(),
//            re.getClassName());
        LOG.info("The exception is expected.", re);
      }

      cluster.shutdown();
      
      log("Normal DataNode upgrade", numDirs);
      cluster = createCluster();
      UpgradeUtilities.createDataNodeStorageDirs(dataNodeDirs, "current");
      cluster.startDataNodes(conf, 1, false, StartupOption.REGULAR, null);
      checkDataNode(dataNodeDirs, UpgradeUtilities.getCurrentBlockPoolID(null));
      cluster.shutdown();
      UpgradeUtilities.createEmptyDirs(dataNodeDirs);
      
      
      log("DataNode upgrade with existing previous dir", numDirs);
      cluster = createCluster();
      UpgradeUtilities.createDataNodeStorageDirs(dataNodeDirs, "current");
      UpgradeUtilities.createDataNodeStorageDirs(dataNodeDirs, "previous");
      cluster.startDataNodes(conf, 1, false, StartupOption.REGULAR, null);
      checkDataNode(dataNodeDirs, UpgradeUtilities.getCurrentBlockPoolID(null));
      cluster.shutdown();
      UpgradeUtilities.createEmptyDirs(dataNodeDirs);

      log("DataNode upgrade with future stored layout version in current", numDirs);
      cluster = createCluster();
      baseDirs = UpgradeUtilities.createDataNodeStorageDirs(dataNodeDirs, "current");
      storageInfo = new StorageInfo(Integer.MIN_VALUE,
          UpgradeUtilities.getCurrentNamespaceID(cluster),
          UpgradeUtilities.getCurrentClusterID(cluster),
          UpgradeUtilities.getCurrentFsscTime(cluster), NodeType.DATA_NODE,
          UpgradeUtilities.getCurrentBlockPoolID(cluster));
      
      UpgradeUtilities.createDataNodeVersionFile(baseDirs, storageInfo,
          UpgradeUtilities.getCurrentBlockPoolID(cluster));
      
      startBlockPoolShouldFail(StartupOption.REGULAR, UpgradeUtilities
          .getCurrentBlockPoolID(null));
      cluster.shutdown();
      UpgradeUtilities.createEmptyDirs(dataNodeDirs);
      
      log("DataNode upgrade with newer fsscTime in current", numDirs);
      cluster = createCluster();
      baseDirs = UpgradeUtilities.createDataNodeStorageDirs(dataNodeDirs, "current");
      storageInfo = new StorageInfo(HdfsConstants.DATANODE_LAYOUT_VERSION,
          UpgradeUtilities.getCurrentNamespaceID(cluster),
          UpgradeUtilities.getCurrentClusterID(cluster), Long.MAX_VALUE,
          NodeType.DATA_NODE, UpgradeUtilities.getCurrentBlockPoolID(cluster));
          
      UpgradeUtilities.createDataNodeVersionFile(baseDirs, storageInfo, 
          UpgradeUtilities.getCurrentBlockPoolID(cluster));
      // Ensure corresponding block pool failed to initialized
      startBlockPoolShouldFail(StartupOption.REGULAR, UpgradeUtilities
          .getCurrentBlockPoolID(null));
      cluster.shutdown();
      UpgradeUtilities.createEmptyDirs(dataNodeDirs);

    } // end numDir loop
    
  }
   
  private void deleteStorageFilesWithPrefix(String[] nameNodeDirs, String prefix)
  throws Exception {
    for (String baseDirStr : nameNodeDirs) {
      File baseDir = new File(baseDirStr);
      File currentDir = new File(baseDir, "current");
      for (File f : currentDir.listFiles()) {
        if (f.getName().startsWith(prefix)) {
          assertTrue("Deleting " + f, f.delete());
        }
      }
    }
  }

  @Test(expected=IOException.class)
  public void testUpgradeFromPreUpgradeLVFails() throws IOException {
    // Upgrade from versions prior to Storage#LAST_UPGRADABLE_LAYOUT_VERSION
    // is not allowed
    Storage.checkVersionUpgradable(Storage.LAST_PRE_UPGRADE_LAYOUT_VERSION + 1);
    fail("Expected IOException is not thrown");
  }
  
  @Ignore
  public void test203LayoutVersion() {
    for (int lv : Storage.LAYOUT_VERSIONS_203) {
      assertTrue(Storage.is203LayoutVersion(lv));
    }
  }
  
  public static void main(String[] args) throws Exception {
    TestDFSUpgrade t = new TestDFSUpgrade();
    TestDFSUpgrade.initialize();
    t.testUpgrade();
  }
}


