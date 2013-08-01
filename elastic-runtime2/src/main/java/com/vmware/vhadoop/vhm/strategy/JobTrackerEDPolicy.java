/***************************************************************************
* Copyright (c) 2013 VMware, Inc. All Rights Reserved.
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
***************************************************************************/

package com.vmware.vhadoop.vhm.strategy;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import com.vmware.vhadoop.api.vhm.ClusterMap;
import com.vmware.vhadoop.api.vhm.HadoopActions;
import com.vmware.vhadoop.api.vhm.HadoopActions.HadoopClusterInfo;
import com.vmware.vhadoop.api.vhm.VCActions;
import com.vmware.vhadoop.api.vhm.strategy.EDPolicy;
import com.vmware.vhadoop.util.CompoundStatus;
import com.vmware.vhadoop.util.VhmLevel;
import com.vmware.vhadoop.vhm.AbstractClusterMapReader;

public class JobTrackerEDPolicy extends AbstractClusterMapReader implements EDPolicy {
   private static final Logger _log = Logger.getLogger(JobTrackerEDPolicy.class.getName());

   private final HadoopActions _hadoopActions;
   private final VCActions _vcActions;

   public JobTrackerEDPolicy(HadoopActions hadoopActions, VCActions vcActions) {
      _hadoopActions = hadoopActions;
      _vcActions = vcActions;
   }

   @Override
   public Set<String> enableTTs(Set<String> toEnable, int totalTargetEnabled, String clusterId) throws Exception {
      Map<String, String> hostNames = null;
      HadoopClusterInfo hadoopCluster = null;
      Set<String> activeVmIds = null;

      ClusterMap clusterMap = null;
      try {
         clusterMap = getAndReadLockClusterMap();
         hadoopCluster = clusterMap.getHadoopInfoForCluster(clusterId);
         hostNames = clusterMap.getDnsNamesForVMs(toEnable);
      } finally {
         unlockClusterMap(clusterMap);
      }

      if ((hostNames != null) && (hadoopCluster != null) && (hadoopCluster.getJobTrackerIpAddr() != null)) {
         CompoundStatus status = getCompoundStatus();

         int enable = toEnable.size();
         _log.log(VhmLevel.USER, "<%C"+clusterId+"%C>: enabling "+enable+" task tracker"+(enable != 1 ? "s" : ""));

         /* TODO: Legacy code returns a CompoundStatus rather than modifying thread local version. Ideally it would be refactored for consistency */
         _hadoopActions.recommissionTTs(hostNames, hadoopCluster);
         if (_vcActions.changeVMPowerState(toEnable, true) == null) {
            status.registerTaskFailed(false, "Failed to change VM power state in VC");
         } else {
            if (status.screenStatusesForSpecificFailures(new String[]{VCActions.VC_POWER_ON_STATUS_KEY})) {
               activeVmIds = _hadoopActions.checkTargetTTsSuccess("Recommission", hostNames, totalTargetEnabled, hadoopCluster);
            }
         }
      }
      return activeVmIds;
   }

   @Override
   public Set<String> disableTTs(Set<String> toDisable, int totalTargetEnabled, String clusterId) throws Exception {
      Map<String, String> hostNames = null;
      HadoopClusterInfo hadoopCluster = null;
      Set<String> activeVmIds = null;

      ClusterMap clusterMap = null;
      try {
         clusterMap = getAndReadLockClusterMap();
         hadoopCluster = clusterMap.getHadoopInfoForCluster(clusterId);
         hostNames = clusterMap.getDnsNamesForVMs(toDisable);
      } finally {
         unlockClusterMap(clusterMap);
      }

      if ((hostNames != null) && (hadoopCluster != null) && (hadoopCluster.getJobTrackerIpAddr() != null)) {
         CompoundStatus status = getCompoundStatus();

         int disable = toDisable.size();
         _log.log(VhmLevel.USER, "<%C"+clusterId+"%C>: disabling "+disable+" task tracker"+(disable != 1 ? "s" : ""));

         /* TODO: Legacy code returns a CompoundStatus rather than modifying thread local version. Ideally it would be refactored for consistency */
         _hadoopActions.decommissionTTs(hostNames, hadoopCluster);
         if (status.screenStatusesForSpecificFailures(new String[]{"decomRecomTTs"})) {
            activeVmIds = _hadoopActions.checkTargetTTsSuccess("Decommission", hostNames, totalTargetEnabled, hadoopCluster);
         }
         if (_vcActions.changeVMPowerState(toDisable, false) == null) {
            status.registerTaskFailed(false, "Failed to change VM power state in VC");
         }
      }
      return getSuccessfullyDisabledVmIds(toDisable, activeVmIds);
   }

   private Set<String> getSuccessfullyDisabledVmIds(Set<String> toDisable, Set<String> activeVmIds) {
      Set<String> result = new HashSet<String>();
      /* JG: If hostnames are screwed up (e.g., become localhost, etc.), activeVmIds can be null */
      if (activeVmIds == null) {
         return result;
      }
      for (String testDisabled : toDisable) {
         if (!activeVmIds.contains(testDisabled)) {
            result.add(testDisabled);
         }
      }
      return result;
   }

   @Override
   public Set<String> getActiveTTs(String clusterId) throws Exception {
      HadoopClusterInfo hadoopCluster = null;

      ClusterMap clusterMap = null;
      try {
         clusterMap = getAndReadLockClusterMap();
         hadoopCluster = clusterMap.getHadoopInfoForCluster(clusterId);
      } finally {
         unlockClusterMap(clusterMap);
      }

      if ((hadoopCluster != null) && (hadoopCluster.getJobTrackerIpAddr() != null)) {
         return _hadoopActions.getActiveTTs(hadoopCluster, 0);
      }
      return null;
   }

}
