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

package com.vmware.vhadoop.api.vhm.strategy;

import java.util.*;

import com.vmware.vhadoop.api.vhm.ClusterMapReader;

/* Takes a set of VMs and either enables or disables them, based on whatever strategy it needs */
public interface EDPolicy extends ClusterMapReader {
   public static final String ACTIVE_TTS_STATUS_KEY = "getActiveStatus";
   /* Caller should expect this to block - returns the VM IDs that were successfully enabled */
   /* Note that this method may return null in the case of an error */
   Set<String> enableTTs(Set<String> toEnable, int totalTargetEnabled, String clusterId) throws Exception;

   Set<String> enableTTs(Map<String, Object> toEnable, int totalTargetEnabled, String clusterId) throws Exception;

   /* Caller should expect this to block - returns the VM IDs that were successfully disabled */
   /* Note that this method may return null in the case of an error */
   Set<String> disableTTs(Set<String> toDisable, int totalTargetEnabled, String clusterId) throws Exception;

   Set<String> disableTTs(Map<String, Object> toDisable, int totalTargetEnabled, String clusterId) throws Exception;

   /* Note that this method may return null in the case of an error */
   Set<String> getActiveTTs(String clusterId) throws Exception;
}
