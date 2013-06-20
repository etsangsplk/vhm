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

package com.vmware.vhadoop.vhm;

import java.io.IOException;

import org.junit.After;
import org.junit.Before;

import com.vmware.vhadoop.api.vhm.events.EventConsumer;
import com.vmware.vhadoop.api.vhm.events.EventProducer;
import com.vmware.vhadoop.model.Orchestrator;
import com.vmware.vhadoop.model.scenarios.Serengeti.Master;
import com.vmware.vhadoop.util.ThreadLocalCompoundStatus;

abstract public class ModelTestBase extends AbstractClusterMapReader implements EventProducer {
   VHM _vhm;
   Orchestrator _orchestrator;
   Master _clusterA;
   BootstrapMain _bootstrap;
   EventConsumer _consumer;

   long startTime;
   /** default timeout is two decision cycles plus warm up/cool down */
   long timeout = (2 * LIMIT_CYCLE_TIME) + TEST_WARM_UP_TIME + TEST_COOLDOWN_TIME;

   static int TEST_WARM_UP_TIME = 20000;
   static int TEST_COOLDOWN_TIME = 10000;
   static int LIMIT_CYCLE_TIME = 1000000;

   public ModelTestBase() throws IOException, ClassNotFoundException {
      /* force this to load so that the springframework binding is done before we invoke tests */
      ClassLoader.getSystemClassLoader().loadClass("com.vmware.vhadoop.vhm.vc.VcVlsi");
   }

   @After
   @Before
   public void resetSingletons() {
      MultipleReaderSingleWriterClusterMapAccess.destroy();
   }

   VHM init() {
      _bootstrap = new ModelController(null, null, _orchestrator);
      return _bootstrap.initVHM(new ThreadLocalCompoundStatus());
   }

   protected void startVHM() {
      _vhm = init();
      _vhm.registerEventProducer(this);
      _vhm.start();
   }

   protected void setTimeout(long millis) {
      timeout = millis;
   }

   protected long timeout() {
      if (startTime == 0) {
         startTime = System.currentTimeMillis();
         return timeout;
      } else {
         return startTime + timeout - System.currentTimeMillis();
      }
   }

   @Override
   public void registerEventConsumer(EventConsumer vhm) {
      _consumer = vhm;
   }

   @Override
   public void start(EventProducerStoppingCallback callback) {
      /* noop */
   }

   @Override
   public void stop() {
      /* noop */
   }

   @Override
   public boolean isStopped() {
      // TODO Auto-generated method stub
      return false;
   }
}
