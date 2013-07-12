package com.vmware.vhadoop.vhm.model.scenarios;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.logging.Logger;

import com.vmware.vhadoop.vhm.model.api.Allocation;
import com.vmware.vhadoop.vhm.model.os.Linux;
import com.vmware.vhadoop.vhm.model.vcenter.VirtualCenter;


public class FaultInjectionSerengeti extends Serengeti {
   private static Logger _log = Logger.getLogger(FaultInjectionSerengeti.class.getName());

   public FaultInjectionSerengeti(String id, VirtualCenter vCenter) {
      super(id, vCenter);

      /* make sure that masters are created using our enterprise template */
      masterOva = new MasterTemplate();
   }

   /**
    * Hides the template defined in Serengeti
    */
   class MasterTemplate extends Serengeti.MasterTemplate {
      @Override
      public Master create(VirtualCenter vCenter, String id, Allocation capacity) {
         Master master = new Master(vCenter, id, capacity);
         master.install(new Linux("Linux"));

         return master;
      }
   }

   public class Master extends Serengeti.Master {
      Queue<Integer> recommissionFailures = new LinkedList<Integer>();
      Queue<Integer> decommissionFailures = new LinkedList<Integer>();
      Queue<Integer> expectedFailureMessages = new LinkedList<Integer>();

      Map<Integer,String> expectedResponse = new HashMap<Integer,String>();

      Master(VirtualCenter vCenter, String id, Allocation capacity) {
         super(vCenter, id, capacity);
      }

      @Override
      public synchronized String enable(String hostname) {
         if (!recommissionFailures.isEmpty()) {
            Integer injectedError = recommissionFailures.poll();
            _log.info(name()+": injecting enable failure: "+injectedError);
            expectedFailureMessages.add(injectedError);
            return injectedError.toString();
         } else {
            return super.enable(hostname);
         }
      }

      @Override
      public synchronized String disable(String hostname) {
         if (!decommissionFailures.isEmpty()) {
            Integer injectedError = decommissionFailures.poll();
            _log.info(name()+": injecting disable failure: "+injectedError);
            expectedFailureMessages.add(injectedError);
            return injectedError.toString();
         } else {
            return super.disable(hostname);
         }
      }

      public synchronized void queueRecommissionFailure(Integer errorCode) {
         recommissionFailures.add(errorCode);
      }

      public synchronized void queueDecommissionFailure(Integer errorCode) {
         decommissionFailures.add(errorCode);
      }
   }
}