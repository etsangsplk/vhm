package com.vmware.vhadoop.vhm;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

import com.vmware.vhadoop.api.vhm.HadoopActions.HadoopClusterInfo;
import com.vmware.vhadoop.api.vhm.ClusterMap.ExtraInfoToScaleStrategyMapper;
import com.vmware.vhadoop.api.vhm.events.ClusterScaleCompletionEvent;
import com.vmware.vhadoop.api.vhm.events.ClusterStateChangeEvent.VMEventData;
import com.vmware.vhadoop.api.vhm.strategy.ScaleStrategy;
import com.vmware.vhadoop.vhm.events.ClusterScaleDecision;
import com.vmware.vhadoop.vhm.events.ScaleStrategyChangeEvent;
import com.vmware.vhadoop.vhm.events.VMRemovedFromClusterEvent;
import com.vmware.vhadoop.vhm.events.VMUpdatedEvent;

public class ClusterMapTest extends AbstractJUnitTest {
   ClusterMapImpl _clusterMap;

   @Override
   void processNewEventData(VMEventData eventData) {
      _clusterMap.handleClusterEvent(new VMUpdatedEvent(eventData));
   }
   
   @Override
   void registerScaleStrategy(ScaleStrategy scaleStrategy) {
      _clusterMap.registerScaleStrategy(scaleStrategy);
   }

   @Before
   public void initialize() {
      _clusterMap = new ClusterMapImpl(new ExtraInfoToScaleStrategyMapper() {
         @Override
         public String getStrategyKey(VMEventData vmd) {
            if ((vmd._masterVmData != null) && (vmd._masterVmData._enableAutomation)) {
               return AUTO_SCALE_STRATEGY_KEY;
            }
            return DEFAULT_SCALE_STRATEGY_KEY;
         }
      });
   }
   

   @Test
   public void getAllKnownClusterIds() {
      int numClusterIds = 3;
      populateSimpleClusterMap(numClusterIds, 4, false);
      String[] toTest = _clusterMap.getAllKnownClusterIds();
      assertEquals(numClusterIds, toTest.length);
      List<String> testNames = Arrays.asList(toTest);
      for (String masterVmName : _masterVmNames) {
         assertTrue(testNames.contains(getClusterIdForMasterVmName(masterVmName)));
      }
   }

   @Test
   public void getClusterIdForFolder() {
      int numClusterIds = 3;
      populateSimpleClusterMap(numClusterIds, 4, false);
      assertEquals(numClusterIds, _clusterNames.size());
      int cntr = 0;
      for (String clusterName : _clusterNames) {
         String folderName = getFolderNameForClusterName(clusterName);
         String clusterId = _clusterMap.getClusterIdForFolder(folderName);
         assertEquals(deriveClusterIdFromClusterName(clusterName), clusterId);
         
         String newFolderName = "NEW_FOLDER"+cntr++;
         _clusterMap.associateFolderWithCluster(clusterId, newFolderName);
         assertEquals(clusterId, _clusterMap.getClusterIdForFolder(newFolderName));
      }

      /* Negative tests */
      assertNull(_clusterMap.getClusterIdForFolder("bogus"));
      assertNull(_clusterMap.getClusterIdForFolder(null));
   }
   
   @Test
   public void getClusterIdForVm() {
      int numClusterIds = 3;
      int numVmsPerCluster = 4;
      populateSimpleClusterMap(numClusterIds, numVmsPerCluster, false);
      assertEquals(numClusterIds * numVmsPerCluster, _vmNames.size());
      for (String vmName : _vmNames) {
         String vmId = getVmIdFromVmName(vmName);
         assertEquals(deriveClusterIdFromVmName(vmName), _clusterMap.getClusterIdForVm(vmId));
      }
      
      /* Negative tests */
      assertNull(_clusterMap.getClusterIdForVm("bogus"));
      assertNull(_clusterMap.getClusterIdForVm(null));
   }
   
   @Test
   public void getDnsNameForVMs() {
      int numClusterIds = 3;
      populateSimpleClusterMap(numClusterIds, 4, false);
      Set<String> vmIds = getVmIdsFromVmNames(_vmNames);
      Set<String> dnsNames = _clusterMap.getDnsNameForVMs(vmIds);
      assertEquals(_vmNames.size(), dnsNames.size());
      for (String vmName : _vmNames) {
         assertTrue(dnsNames.contains(getDnsNameFromVmName(vmName)));
      }

      /* Negative tests */
      assertNull(_clusterMap.getDnsNameForVMs(getBogusSet()));
      assertNull(_clusterMap.getDnsNameForVMs(getEmptySet()));
      assertNull(_clusterMap.getDnsNameForVMs(null));
   }

   @Test
   public void getHadoopInfoForCluster() {
      int numClusterIds = 3;
      populateSimpleClusterMap(numClusterIds, 4, false);
      for (String clusterName : _clusterNames) {
         String clusterId = deriveClusterIdFromClusterName(clusterName);
         HadoopClusterInfo hci = _clusterMap.getHadoopInfoForCluster(clusterId);
         assertNotNull(hci);
         assertEquals(deriveMasterIpAddrFromClusterId(clusterId), hci.getJobTrackerAddr());
         assertEquals((Integer)DEFAULT_PORT, hci.getJobTrackerPort());
      }

      /* Negative tests */
      assertNull(_clusterMap.getHadoopInfoForCluster("bogus"));
      assertNull(_clusterMap.getHadoopInfoForCluster(null));
   }

   @Test
   public void getHostIdForVM() {
      int numClusterIds = 3;
      populateClusterPerHost(numClusterIds, 4, false);
      for (String vmName : _vmNames) {
         String hostId = _clusterMap.getHostIdForVm(getVmIdFromVmName(vmName));
         String expected = deriveHostIdFromVmName(vmName);
         assertEquals(expected, hostId);
      }

      /* Negative tests */
      assertNull(_clusterMap.getHostIdForVm("bogus"));
      assertNull(_clusterMap.getHostIdForVm(null));
   }

   @Test
   public void getHostIdsForVMs() {
      int numClusterIds = 3;
      populateClusterPerHost(numClusterIds, 4, false);
      Set<String> vmIds = getVmIdsFromVmNames(_vmNames);
      Map<String, String> hostIds = _clusterMap.getHostIdsForVMs(vmIds);
      assertEquals(vmIds.size(), hostIds.keySet().size());
      for (String vmId : vmIds) {
         String hostId = hostIds.get(vmId);
         assertNotNull(hostId);
         assertEquals(deriveHostIdFromVmId(vmId), hostId);
      }

      /* Negative tests */
      assertNull(_clusterMap.getHostIdsForVMs(getBogusSet()));
      assertNull(_clusterMap.getHostIdsForVMs(getEmptySet()));
      assertNull(_clusterMap.getHostIdsForVMs(null));
   }
   
   @Test
   public void getLastClusterScaleCompletionEvent() {
      int numClusterIds = 3;
      populateSimpleClusterMap(numClusterIds, 4, false);
      for (String clusterName : _clusterNames) {
         String clusterId = deriveClusterIdFromClusterName(clusterName);
         assertNull(_clusterMap.getLastClusterScaleCompletionEvent(clusterId));

         ClusterScaleCompletionEvent cse = new ClusterScaleDecision(clusterId);
         _clusterMap.handleCompletionEvent(cse);
         assertEquals(cse, _clusterMap.getLastClusterScaleCompletionEvent(clusterId));
         
         /* Check that most recent event is returned */
         ClusterScaleCompletionEvent cse2 = new ClusterScaleDecision(clusterId);
         _clusterMap.handleCompletionEvent(cse2);
         assertEquals(cse2, _clusterMap.getLastClusterScaleCompletionEvent(clusterId));
      }

      /* Negative tests */
      assertNull(_clusterMap.getLastClusterScaleCompletionEvent(null));
      assertNull(_clusterMap.getLastClusterScaleCompletionEvent("bogus"));
   }
   
   @Test
   public void testScaleStrategies() {
      populateClusterSameHost(CLUSTER_NAME_PREFIX+0, "DEFAULT_HOST", 4, false, false, 0);
      populateClusterSameHost(CLUSTER_NAME_PREFIX+1, "DEFAULT_HOST", 4, false, false, 0);
      populateClusterSameHost(CLUSTER_NAME_PREFIX+2, "DEFAULT_HOST", 4, false, true, 0);

      String cid0 = deriveClusterIdFromClusterName(CLUSTER_NAME_PREFIX+0);
      ScaleStrategy ss0 = _clusterMap.getScaleStrategyForCluster(cid0);
      assertEquals(DEFAULT_SCALE_STRATEGY_KEY, ss0.getKey());

      String cid1 = deriveClusterIdFromClusterName(CLUSTER_NAME_PREFIX+1);
      ScaleStrategy ss1 = _clusterMap.getScaleStrategyForCluster(cid1);
      assertEquals(DEFAULT_SCALE_STRATEGY_KEY, ss1.getKey());

      String cid2 = deriveClusterIdFromClusterName(CLUSTER_NAME_PREFIX+2);
      ScaleStrategy ss2 = _clusterMap.getScaleStrategyForCluster(cid2);
      assertEquals(AUTO_SCALE_STRATEGY_KEY, ss2.getKey());

      ScaleStrategy ssBogus = _clusterMap.getScaleStrategyForCluster("bogus");
      assertNull(ssBogus);
      
      /* Change ss1 to use AUTO */
      _clusterMap.handleClusterEvent(new ScaleStrategyChangeEvent(cid1, AUTO_SCALE_STRATEGY_KEY));
      
      ss1 = _clusterMap.getScaleStrategyForCluster(cid1);
      assertEquals(AUTO_SCALE_STRATEGY_KEY, ss1.getKey());
      assertEquals(AUTO_SCALE_STRATEGY_KEY, _clusterMap.getScaleStrategyKey(cid1));
      
      /* Negative tests */
      assertNull(_clusterMap.getScaleStrategyForCluster(null));
      assertNull(_clusterMap.getScaleStrategyForCluster("bogus"));
      
      assertNull(_clusterMap.getScaleStrategyKey(null));
      assertNull(_clusterMap.getScaleStrategyKey("bogus"));
   }
   
   @Test
   public void powerStateTests() {
      /* Create 3 clusters, each with 3 compute VMs and a master. Two have vms powered one and one cluster is powered off */
      populateClusterSameHost(CLUSTER_NAME_PREFIX+0, "DEFAULT_HOST1", 4, false, false, 0);
      populateClusterSameHost(CLUSTER_NAME_PREFIX+1, "DEFAULT_HOST1", 4, true, false, 0);
      populateClusterSameHost(CLUSTER_NAME_PREFIX+2, "DEFAULT_HOST2", 4, true, true, 0);
      
      /* Note expected result is 3, not 4, since 3 VMs are compute VMs and 1 is master */
      Integer[][] expectedSizes1 = new Integer[][]{new Integer[]{3, null}, new Integer[]{null, 3}, new Integer[]{null, 3}};
      boolean[] expectMatch1 = new boolean[]{true, true, false};
      boolean[] expectMatch2 = new boolean[]{false, false, true};
      
      /* For each cluster */
      for (int i=0; i<3; i++) {
         String clusterName = CLUSTER_NAME_PREFIX+i;
         String clusterId = deriveClusterIdFromClusterName(clusterName);

         /* For each power state */
         for (int j=0; j<2; j++) {
            boolean expectedPowerState = (j==1);
            Set<String> computeVMs = _clusterMap.listComputeVMsForClusterAndPowerState(clusterId, expectedPowerState);
            Integer result = (computeVMs == null) ? null : computeVMs.size();
            assertEquals(expectedSizes1[i][j], result);
            
            if (computeVMs != null) {
               assertTrue(_clusterMap.checkPowerStateOfVms(computeVMs, expectedPowerState));
               assertFalse(_clusterMap.checkPowerStateOfVms(computeVMs, !expectedPowerState));
               
               if (expectedPowerState) {
                  assertNotNull(_clusterMap.getPowerOnTimeForVm(computeVMs.iterator().next()));
               }
               
               assertEquals(clusterId, _clusterMap.getClusterIdFromVMs(new ArrayList<String>(computeVMs)));

               /* Check that the compute VM names returned contain the cluster name */
               for (String computeVM : computeVMs) {
                  assertTrue(computeVM.contains(clusterName));
               }

               /* Add host restrictions and check results against original results */
               Set<String> computeVMs2 = _clusterMap.listComputeVMsForClusterHostAndPowerState(clusterId, MOREF_PREFIX+"DEFAULT_HOST1", (j==1));
               if (expectMatch1[i]) {
                  assertEquals(computeVMs.size(), computeVMs2.size());
               } else if (computeVMs2 != null) {
                  assertTrue(computeVMs.size() != computeVMs2.size());
               }
               
               computeVMs2 = _clusterMap.listComputeVMsForClusterHostAndPowerState(clusterId, MOREF_PREFIX+"DEFAULT_HOST2", (j==1));
               if (expectMatch2[i]) {
                  assertEquals(computeVMs.size(), computeVMs2.size());
               } else if (computeVMs2 != null) {
                  assertTrue(computeVMs.size() != computeVMs2.size());
               }
            }
         }

         Set<String> hostIds = _clusterMap.listHostsWithComputeVMsForCluster(clusterId);
         assertEquals(1, hostIds.size());
      }
      
      assertEquals(3, _clusterMap.listComputeVMsForPowerState(false).size());
      assertEquals(6, _clusterMap.listComputeVMsForPowerState(true).size());

      /* Negative tests */
      assertNull(_clusterMap.listComputeVMsForClusterAndPowerState("bogus", false));
      assertNull(_clusterMap.listComputeVMsForClusterAndPowerState(null, false));
      
      assertNull(_clusterMap.listComputeVMsForClusterHostAndPowerState("bogus", "bogus", false));
      assertNull(_clusterMap.listComputeVMsForClusterHostAndPowerState(null, null, false));
      
      assertNull(_clusterMap.checkPowerStateOfVms(null, false));
      assertNull(_clusterMap.checkPowerStateOfVms(getEmptySet(), false));
      assertNull(_clusterMap.checkPowerStateOfVms(getBogusSet(), false));
      
      assertNull(_clusterMap.listHostsWithComputeVMsForCluster("bogus"));
      assertNull(_clusterMap.listHostsWithComputeVMsForCluster(null));
      
      assertNull(_clusterMap.getClusterIdFromVMs(null));
      assertNull(_clusterMap.getClusterIdFromVMs(new ArrayList<String>(getEmptySet())));
      assertNull(_clusterMap.getClusterIdFromVMs(new ArrayList<String>(getBogusSet())));
      
      assertNull(_clusterMap.getPowerOnTimeForVm("bogus"));
      assertNull(_clusterMap.getPowerOnTimeForVm(null));
   }
   
   @Test
   public void getVCPU() {
      populateClusterSameHost(CLUSTER_NAME_PREFIX+0, "DEFAULT_HOST1", 4, false, false, 0);
      for (String vmName : _vmNames) {
         String vmId = getVmIdFromVmName(vmName);
         assertEquals((Integer)DEFAULT_VCPUS, _clusterMap.getNumVCPUsForVm(vmId));
      }
      
      /* Negative tests */
      assertNull(_clusterMap.getNumVCPUsForVm("bogus"));
      assertNull(_clusterMap.getNumVCPUsForVm(null));
   }

   
   @Test
   public void testRemoveVM() {
      String clusterName = CLUSTER_NAME_PREFIX+0;
      String clusterId = deriveClusterIdFromClusterName(clusterName);
      populateClusterSameHost(clusterName, "DEFAULT_HOST1", 4, false, false, 0);
      Set<String> vms = _clusterMap.listComputeVMsForClusterAndPowerState(clusterId, false);
      int runningTotal = 3;
      assertEquals(runningTotal, vms.size());
      
      /* Remove the compute VMs */
      for (String vmId : vms) {
         --runningTotal;
         _clusterMap.handleClusterEvent(new VMRemovedFromClusterEvent(vmId));
         Set<String> remaining = _clusterMap.listComputeVMsForClusterAndPowerState(clusterId, false);
         if (runningTotal > 0) {
            assertEquals(runningTotal, remaining.size());
            assertFalse(remaining.contains(vmId));
         } else {
            assertNull(remaining);
         }
      }
      
      /* Cluster should still be there */
      String masterVmId = getMasterVmIdForCluster(clusterName);
      assertEquals(clusterId, _clusterMap.getClusterIdForVm(masterVmId));
      
      /* Remove master VM and the cluster should be removed also */
      _clusterMap.handleClusterEvent(new VMRemovedFromClusterEvent(masterVmId));
      assertNull(_clusterMap.getClusterIdForVm(getVmIdFromVmName(masterVmId)));
      assertNull(_clusterMap.getAllKnownClusterIds());
   }
   
   @Test
   public void invokeGettersOnEmptyClusterMap() {
      Set<String> vms = new HashSet<String>();
      vms.add("foo");
      assertNull(_clusterMap.getAllKnownClusterIds());
      assertNull(_clusterMap.getClusterIdForFolder("foo"));
      assertNull(_clusterMap.getClusterIdForVm("foo"));
      assertNull(_clusterMap.getDnsNameForVMs(vms));
      assertNull(_clusterMap.getHadoopInfoForCluster("foo"));
      assertNull(_clusterMap.getHostIdForVm("foo"));
      assertNull(_clusterMap.getHostIdsForVMs(vms));
      assertNull(_clusterMap.getLastClusterScaleCompletionEvent("foo"));
      assertNull(_clusterMap.getScaleStrategyForCluster("foo"));
      assertNull(_clusterMap.listComputeVMsForClusterAndPowerState("foo", false));
      assertNull(_clusterMap.listComputeVMsForClusterHostAndPowerState("foo", "foo", false));
      assertNull(_clusterMap.listComputeVMsForPowerState(false));
      assertNull(_clusterMap.listHostsWithComputeVMsForCluster("foo"));
      assertNull(_clusterMap.checkPowerStateOfVms(vms, false));
      assertNull(_clusterMap.getNumVCPUsForVm("foo"));
      assertNull(_clusterMap.getPowerOnTimeForVm("foo"));
   }
}
