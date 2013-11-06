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

import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import com.vmware.vhadoop.api.vhm.VCActions;
import com.vmware.vhadoop.api.vhm.strategy.EDPolicy;
import com.vmware.vhadoop.util.VhmLevel;
import com.vmware.vhadoop.vhm.AbstractClusterMapReader;

public class DumbEDPolicy extends AbstractClusterMapReader implements EDPolicy {
   private static final Logger _log = Logger.getLogger(DumbEDPolicy.class.getName());

   private final VCActions _vcActions;

   public DumbEDPolicy(VCActions vcActions) {
      _vcActions = vcActions;
   }

   @Override
   public Set<String> enableTTs(Set<String> toEnable, int totalTargetEnabled, String clusterId) throws Exception {
      int enable = toEnable.size();
      _log.log(VhmLevel.USER, "<%C"+clusterId+"%C>: enabling "+enable+" task tracker"+(enable != 1 ? "s" : ""));
      if (_vcActions.changeVMPowerState(toEnable, true) == null) {
         getCompoundStatus().registerTaskFailed(false, "Failed to change VM power state");
      }
      return toEnable;     /* Clue is in the title: Dumby assume it worked */
   }


   @Override
   public Set<String> disableTTs(Set<String> toDisable, int totalTargetEnabled, String clusterId) throws Exception {
      int disable = toDisable.size();
      _log.log(VhmLevel.USER, "<%C"+clusterId+"%C>: disabling "+disable+" task tracker"+(disable != 1 ? "s" : ""));
      if (_vcActions.changeVMPowerState(toDisable, false) == null) {
         getCompoundStatus().registerTaskFailed(false, "Failed to change VM power state");
      }
      return toDisable;    /* Clue is in the title: Dumby assume it worked */
   }

   @Override
   public Set<String> getActiveTTs(String clusterId) throws Exception {
      return null;
   }

   @Override
   public Set<String> enableTTs(Map<String, Object> toEnable, int totalTargetEnabled, String clusterId) throws Exception {
      return enableTTs(toEnable.keySet(), totalTargetEnabled, clusterId);
   }

   @Override
   public Set<String> disableTTs(Map<String, Object> toDisable, int totalTargetEnabled, String clusterId) throws Exception {
      return disableTTs(toDisable.keySet(), totalTargetEnabled, clusterId);
   }

}
