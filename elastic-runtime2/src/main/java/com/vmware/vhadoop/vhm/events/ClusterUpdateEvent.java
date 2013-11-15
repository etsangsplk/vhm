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

package com.vmware.vhadoop.vhm.events;

import java.util.logging.Logger;

import com.vmware.vhadoop.api.vhm.events.ClusterStateChangeEvent;
import com.vmware.vhadoop.util.LogFormatter;

public class ClusterUpdateEvent extends AbstractNotificationEvent implements ClusterStateChangeEvent {
   private String _vmId;
   private SerengetiClusterVariableData _clusterVariableData;
   
   public ClusterUpdateEvent(String vmId, SerengetiClusterVariableData clusterVariableData) {
      super(false, false);
      _vmId = vmId;
      _clusterVariableData = clusterVariableData;
   }
   
   public String getVmId() {
      return _vmId;
   }

   public SerengetiClusterVariableData getClusterVariableData() {
      return _clusterVariableData;
   }

   String getParamListString(Logger logger) {
      String basic = "vmId=<%V"+_vmId+"%V>";
      String detail = LogFormatter.isDetailLogging(logger) ? ", ClusterVariableData="+_clusterVariableData : "";
      return basic+detail;
   }

   @Override
   public String toString(Logger logger) {
      return "ClusterUpdateEvent{"+getParamListString(logger)+"}";
   }

   @Override
   public String toString() {
      return toString(null);
   }
}
