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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.gson.Gson;
import com.vmware.vhadoop.api.vhm.ClusterMap;
import com.vmware.vhadoop.api.vhm.ClusterMap.ExtraInfoToClusterMapper;
import com.vmware.vhadoop.api.vhm.ClusterMapReader;
import com.vmware.vhadoop.api.vhm.VCActions.VMEventData;
import com.vmware.vhadoop.api.vhm.events.ClusterScaleCompletionEvent;
import com.vmware.vhadoop.api.vhm.events.ClusterScaleEvent;
import com.vmware.vhadoop.api.vhm.events.ClusterStateChangeEvent.SerengetiClusterVariableData;
import com.vmware.vhadoop.api.vhm.events.EventConsumer;
import com.vmware.vhadoop.api.vhm.events.EventProducer;
import com.vmware.vhadoop.api.vhm.strategy.ScaleStrategy;
import com.vmware.vhadoop.util.ThreadLocalCompoundStatus;
import com.vmware.vhadoop.vhm.TrivialClusterScaleEvent.ChannelReporter;
import com.vmware.vhadoop.vhm.TrivialScaleStrategy.TrivialClusterScaleOperation;
import com.vmware.vhadoop.vhm.events.SerengetiLimitInstruction;
import com.vmware.vhadoop.vhm.events.SerengetiLimitInstruction.SerengetiLimitAction;
import com.vmware.vhadoop.vhm.rabbit.RabbitAdaptor.RabbitConnectionCallback;
import com.vmware.vhadoop.vhm.rabbit.VHMJsonReturnMessage;
import com.vmware.vhadoop.vhm.strategy.DumbEDPolicy;
import com.vmware.vhadoop.vhm.strategy.DumbVMChooser;
import com.vmware.vhadoop.vhm.strategy.ManualScaleStrategy;

public class VHMIntegrationTest extends AbstractJUnitTest implements EventProducer, ClusterMapReader {
   VHM _vhm;
   StandaloneSimpleVCActions _vcActions;
   ExtraInfoToClusterMapper _strategyMapper;
   ClusterStateChangeListenerImpl _clusterStateChangeListener;
   TrivialScaleStrategy _trivialScaleStrategy;
   List<Boolean> _isNewClusterResult;

   public static final String STRATEGY_KEY = "StrategyKey";

   /* EventProducer fields */
   EventConsumer _eventConsumer;

   /* ClusterMapReader fields */
   ClusterMapAccess _clusterMapAccess;

   @Override
   public void initialize(final ClusterMapReader parent) {
      if (parent instanceof AbstractClusterMapReader) {
         _clusterMapAccess = ((AbstractClusterMapReader)parent)._clusterMapAccess;
      } else {
         throw new RuntimeException("Unrecognized ClusterMapReader implementation");
      }
   }

   @Override
   void processNewEventData(VMEventData eventData, String expectedClusterName, Set<ClusterScaleEvent> impliedScaleEvents) {
      processNewEventData(eventData);
   }

   void processNewEventData(VMEventData eventData) {
      _vcActions.fakeWaitForUpdatesData(eventData);
      _vcActions.addVMToFolder(eventData._serengetiFolder, eventData._vmMoRef);
   }

   /* Generated whenever particular data in a particular cluster changes */
   private class ClusterDataChangedEvent extends TrivialClusterScaleEvent {
      int _newData;
      public ClusterDataChangedEvent(String clusterId, int newData) {
         super(clusterId, true);
         _newData = newData;
      }
   }

   /* Generated whenever a cluster is discovered that has a non-manual strategy, or if the strategy is switched */
   private class ClusterScaleStrategyNotManualEvent extends TrivialClusterScaleEvent {
      boolean _isNewCluster;
      public ClusterScaleStrategyNotManualEvent(String clusterId, boolean isNewCluster) {
         super(clusterId, true);
         _isNewCluster = isNewCluster;
      }
   }

   @Before
   public void initialize() {
      _vcActions = new StandaloneSimpleVCActions();
      _isNewClusterResult = new ArrayList<Boolean>();
      _clusterStateChangeListener = new ClusterStateChangeListenerImpl(_vcActions, "myFolder");
      _strategyMapper = new ExtraInfoToClusterMapper() {
         @Override
         public String getStrategyKey(SerengetiClusterVariableData cvd, String clusterId) {
            return cvd._enableAutomation ? STRATEGY_KEY : ManualScaleStrategy.MANUAL_SCALE_STRATEGY_KEY;
         }

         @Override
         public Map<String, String> parseExtraInfo(SerengetiClusterVariableData scvd, String clusterId) {
            Map<String, String> result = null;
            if (scvd._minInstances != null) {
               result = new HashMap<String, String>();
               result.put("minKey", scvd._minInstances.toString());
            }
            if (scvd._maxInstances != null) {
               if (result == null) {
                  result = new HashMap<String, String>();
               }
               result.put("maxKey", scvd._maxInstances.toString());
            }
            return result;
         }

         @Override
         public Set<ClusterScaleEvent> getImpliedScaleEventsForUpdate(SerengetiClusterVariableData cvd, String clusterId, boolean isNewCluster, boolean isClusterViable) {
            _isNewClusterResult.add(isNewCluster);
            if (!isClusterViable) {
               return null;
            }
            Set<ClusterScaleEvent> newEvents = new LinkedHashSet<ClusterScaleEvent>();
            if ((cvd != null) && (cvd._enableAutomation != null)) {
               if (cvd._enableAutomation) {
                  newEvents.add(new ClusterScaleStrategyNotManualEvent(clusterId, isNewCluster));
               }
            }
            return newEvents;
         }
      };

      /* TrivialScaleStrategy is the default used as is picked if vmd._masterVmData._enableAutomation is true (see above) */
      _trivialScaleStrategy = new TrivialScaleStrategy(STRATEGY_KEY);
      ManualScaleStrategy manualScaleStrategy = new ManualScaleStrategy(new DumbEDPolicy(_vcActions));
      _vhm = new VHM(_vcActions, new ScaleStrategy[]{_trivialScaleStrategy, manualScaleStrategy},
            _strategyMapper, new ThreadLocalCompoundStatus());
      assertTrue(_vhm.registerEventProducer(_clusterStateChangeListener));
      assertTrue(_vhm.registerEventProducer(this));
      _vhm.registerCollaborator(new DumbVMChooser());
      _vhm.start();
   }

   @After
   public void cleanup() {
      try {
         Thread.sleep(1000);
      } catch (InterruptedException e) {
         // TODO Auto-generated catch block
         e.printStackTrace();
      }
      _vhm.stop(true);
      MultipleReaderSingleWriterClusterMapAccess.destroy();
   }

   /* After updating VHM via the CSCL, wait for the updates to appear in ClusterMap */
   private boolean waitForTargetClusterCount(int clusterCount, long timeoutMillis) {
      Set<String> clusterIds = null;
      long pollTimeMillis = 10;
      long maxIterations = timeoutMillis / pollTimeMillis;
      /* VHM may still be in the process of dealing with the events coming from the CSCL, so need to wait for the expected value */
      while ((clusterIds == null) || (clusterIds.size() != clusterCount)) {
         ClusterMap clusterMap = null;
         try {
            clusterMap = getAndReadLockClusterMap();
            assertNotNull(clusterMap);
            clusterIds = clusterMap.getAllKnownClusterIds();
         } finally {
            unlockClusterMap(clusterMap);
         }
         try {
            Thread.sleep(pollTimeMillis);
         } catch (InterruptedException e) {
            e.printStackTrace();
         }
         if (--maxIterations <= 0) return false;
      }
      return true;
    }

   private Set<ClusterScaleCompletionEvent> waitForClusterScaleCompletionEvents(String clusterId, int numExpected, int timeoutMillis,
         Set<ClusterScaleCompletionEvent> thatsNotInSet) {
      Set<ClusterScaleCompletionEvent> result = new HashSet<ClusterScaleCompletionEvent>();
      int totalExpected = numExpected;
      if (thatsNotInSet != null) {
         result.addAll(thatsNotInSet);
         totalExpected += thatsNotInSet.size();
      }
      int timeoutLeft = timeoutMillis;
      while ((result.size() < totalExpected) && (timeoutLeft > 0)) {
         long timeNow = System.currentTimeMillis();
         ClusterScaleCompletionEvent newEvent = waitForClusterScaleCompletionEvent(clusterId, timeoutLeft, result);
         if (newEvent != null) {
            result.add(newEvent);
         }
         timeoutLeft -= (System.currentTimeMillis() - timeNow);
      }
      if (thatsNotInSet != null) {
         result.removeAll(thatsNotInSet);
      }
      return result;
   }

   private Set<ClusterScaleCompletionEvent> waitForClusterScaleCompletionEvents(String clusterId, int numExpected, int timeoutMillis) {
      return waitForClusterScaleCompletionEvents(clusterId, numExpected, timeoutMillis, null);
   }

   private ClusterScaleCompletionEvent waitForClusterScaleCompletionEvent(String clusterId, int timeoutMillis) {
      return waitForClusterScaleCompletionEvent(clusterId, timeoutMillis, null);
   }

   private ClusterScaleCompletionEvent waitForClusterScaleCompletionEvent(String clusterId, int timeoutMillis,
         Set<ClusterScaleCompletionEvent> thatsNotInSet) {
      ClusterScaleCompletionEvent toWaitFor = null;
      long pollTimeMillis = 10;
      long maxIterations = timeoutMillis / pollTimeMillis;
      boolean nullOrInSet = true;
      /* VHM may still be in the process of dealing with the events coming from the CSCL, so need to wait for the expected value */
      while (nullOrInSet) {
         ClusterMap clusterMap = null;
         try {
            clusterMap = getAndReadLockClusterMap();
            assertNotNull(clusterMap);
            toWaitFor = clusterMap.getLastClusterScaleCompletionEvent(clusterId);
            nullOrInSet = (toWaitFor == null) || ((thatsNotInSet != null) && (thatsNotInSet.contains(toWaitFor)));
         } finally {
            unlockClusterMap(clusterMap);
         }
         try {
            Thread.sleep(pollTimeMillis);
         } catch (InterruptedException e) {
            e.printStackTrace();
         }
         if (--maxIterations <= 0) return null;
      }
      return toWaitFor;
   }

   @Test
   public void sanityTest() {
      int numClusters = 3;
      populateSimpleClusterMap(numClusters, 4, false);    /* Blocks until CSCL has generated all events */
      assertTrue(waitForTargetClusterCount(3, 1000));

      ClusterMap clusterMap = null;
      try {
         clusterMap = getAndReadLockClusterMap();
         assertNotNull(clusterMap);
         Set<String> clusterIds = clusterMap.getAllKnownClusterIds();

         /* Verify that the number of clusters seen by vcActions is reflected in the ClusterMap */
         assertNotNull(clusterIds);
         assertEquals(numClusters, clusterIds.size());
      } finally {
         unlockClusterMap(clusterMap);
      }
   }

   @Test
   public void testInvokeScaleStrategy() {
      int numClusters = 3;
      populateSimpleClusterMap(numClusters, 4, false);    /* Blocks until CSCL has generated all events */
      assertTrue(waitForTargetClusterCount(3, 1000));

      String clusterId = deriveClusterIdFromClusterName(_clusterNames.iterator().next());
      /* Create a ClusterScaleOperation, which controls how a cluster is scaled */
      TrivialClusterScaleOperation tcso = _trivialScaleStrategy.new TrivialClusterScaleOperation();
      /* Add the test ClusterScaleOperation to the test scale strategy */
      _trivialScaleStrategy.setClusterScaleOperation(clusterId, tcso);

      /* Simulate a cluster scale event being triggered from an EventProducer */
      _eventConsumer.placeEventOnQueue(new TrivialClusterScaleEvent(clusterId, false));
      /* Wait for VHM to respond, having invoked the ScaleStrategy */
      assertNotNull(waitForClusterScaleCompletionEvent(clusterId, 2000));

      assertEquals(clusterId, tcso.getClusterId());
      assertNotNull(tcso.getContext());
      assertNotNull(tcso.getThreadLocalCompoundStatus());
   }

   private void testEventOrdering(String clusterId, Set<ClusterScaleCompletionEvent> previousEvents,
         TrivialClusterScaleOperation tcso, int assertNumEventsReturned, int assertHeadOfQueueEventIndex,
         ClusterScaleEvent event0, ClusterScaleEvent event1, ClusterScaleEvent event2) throws InterruptedException {

      ClusterScaleEvent[] events = new ClusterScaleEvent[]{event0, event1, event2};

      _eventConsumer.placeEventCollectionOnQueue(Arrays.asList(events));
      /* Wait for VHM to respond, having invoked the ScaleStrategy */
      previousEvents.add(waitForClusterScaleCompletionEvent(clusterId, 2000, previousEvents));
      assertNotNull(tcso._events);
      assertEquals(assertNumEventsReturned, tcso._events.size());
      /* The _events set should be the LinkedHashSet created in VHM.updateOrCreateClusterScaleEventSet */
      assertTrue(tcso._events.iterator().next() == events[assertHeadOfQueueEventIndex]);
   }

   @Test
   public void testExclusiveEventBehavior() throws InterruptedException {
      Set<ClusterScaleCompletionEvent> previousEvents = new HashSet<ClusterScaleCompletionEvent>();
      int numClusters = 3;
      populateSimpleClusterMap(numClusters, 4, false);    /* Blocks until CSCL has generated all events */
      assertTrue(waitForTargetClusterCount(3, 1000));

      Iterator<String> i = _clusterNames.iterator();
      String clusterId0 = deriveClusterIdFromClusterName(i.next());
      String clusterId1 = deriveClusterIdFromClusterName(i.next());
      String clusterId2 = deriveClusterIdFromClusterName(i.next());

      /* Create a ClusterScaleOperation, which controls how a cluster is scaled */
      TrivialClusterScaleOperation tcso0 = _trivialScaleStrategy.new TrivialClusterScaleOperation();
      /* Add the test ClusterScaleOperation to the test scale strategy */
      _trivialScaleStrategy.setClusterScaleOperation(clusterId0, tcso0);

      TrivialClusterScaleOperation tcso1 = _trivialScaleStrategy.new TrivialClusterScaleOperation();
      _trivialScaleStrategy.setClusterScaleOperation(clusterId1, tcso1);
      TrivialClusterScaleOperation tcso2 = _trivialScaleStrategy.new TrivialClusterScaleOperation();
      _trivialScaleStrategy.setClusterScaleOperation(clusterId2, tcso2);

      /* Identical events that are not exclusive and have different timestamps should all be consolidated
       * The one that survives should be the first one to be added, regardless of timestamp */
      int expectedIndex = 0;
      testEventOrdering(clusterId0, previousEvents, tcso0, 1, expectedIndex,
            new TrivialClusterScaleEvent(clusterId0, false, Long.MAX_VALUE-2),
            new TrivialClusterScaleEvent(clusterId0, false, Long.MAX_VALUE-1),
            new TrivialClusterScaleEvent(clusterId0, false, Long.MAX_VALUE));
      testEventOrdering(clusterId1, previousEvents, tcso1, 1, expectedIndex,
            new TrivialClusterScaleEvent(clusterId1, false, Long.MAX_VALUE),
            new TrivialClusterScaleEvent(clusterId1, false, Long.MAX_VALUE-1),
            new TrivialClusterScaleEvent(clusterId1, false, Long.MAX_VALUE-2));
      testEventOrdering(clusterId2, previousEvents, tcso2, 1, expectedIndex,
            new TrivialClusterScaleEvent(clusterId2, false, Long.MAX_VALUE),
            new TrivialClusterScaleEvent(clusterId2, false, Long.MAX_VALUE),
            new TrivialClusterScaleEvent(clusterId2, false, Long.MAX_VALUE));

      /* Exclusive identical events should be consolidated in the same way as not exclusive ones */
      expectedIndex = 0;
      testEventOrdering(clusterId0, previousEvents, tcso0, 1, expectedIndex,
            new TrivialClusterScaleEvent(clusterId0, true, Long.MAX_VALUE-2),
            new TrivialClusterScaleEvent(clusterId0, true, Long.MAX_VALUE-1),
            new TrivialClusterScaleEvent(clusterId0, true, Long.MAX_VALUE));
      testEventOrdering(clusterId1, previousEvents, tcso1, 1, expectedIndex,
            new TrivialClusterScaleEvent(clusterId1, true, Long.MAX_VALUE),
            new TrivialClusterScaleEvent(clusterId1, true, Long.MAX_VALUE-1),
            new TrivialClusterScaleEvent(clusterId1, true, Long.MAX_VALUE-2));
      testEventOrdering(clusterId2, previousEvents, tcso2, 1, expectedIndex,
            new TrivialClusterScaleEvent(clusterId2, true, Long.MAX_VALUE),
            new TrivialClusterScaleEvent(clusterId2, true, Long.MAX_VALUE),
            new TrivialClusterScaleEvent(clusterId2, true, Long.MAX_VALUE));

      /* Non exclusive different events should not be consolidated - the first one added is the first one returned */
      expectedIndex = 0;
      testEventOrdering(clusterId0, previousEvents, tcso0, 3, expectedIndex,
            new TrivialClusterScaleEvent("vm1", "host1", clusterId0, "routeKey", null, false, Long.MAX_VALUE-2),
            new TrivialClusterScaleEvent("vm2", "host2", clusterId0, "routeKey", null, false, Long.MAX_VALUE-1),
            new TrivialClusterScaleEvent("vm3", "host3", clusterId0, "routeKey", null, false, Long.MAX_VALUE));
      testEventOrdering(clusterId1, previousEvents, tcso1, 3, expectedIndex,
            new TrivialClusterScaleEvent("vm1", "host1", clusterId1, "routeKey", null, false, Long.MAX_VALUE),
            new TrivialClusterScaleEvent("vm2", "host2", clusterId1, "routeKey", null, false, Long.MAX_VALUE-1),
            new TrivialClusterScaleEvent("vm3", "host3", clusterId1, "routeKey", null, false, Long.MAX_VALUE-2));
      testEventOrdering(clusterId2, previousEvents, tcso2, 3, expectedIndex,
            new TrivialClusterScaleEvent("vm1", "host1", clusterId2, "routeKey", null, false, Long.MAX_VALUE),
            new TrivialClusterScaleEvent("vm2", "host2", clusterId2, "routeKey", null, false, Long.MAX_VALUE),
            new TrivialClusterScaleEvent("vm3", "host3", clusterId2, "routeKey", null, false, Long.MAX_VALUE));

      /* Exclusive different events should be consolidated in timestamp order - the most recent being the most recently  */
      expectedIndex = 2;
      testEventOrdering(clusterId0, previousEvents, tcso0, 1, expectedIndex,
            new TrivialClusterScaleEvent("vm1", "host1", clusterId0, "routeKey", null, true, Long.MAX_VALUE-2),
            new TrivialClusterScaleEvent("vm2", "host2", clusterId0, "routeKey", null, true, Long.MAX_VALUE-1),
            new TrivialClusterScaleEvent("vm3", "host3", clusterId0, "routeKey", null, true, Long.MAX_VALUE));
      expectedIndex = 0;
      testEventOrdering(clusterId1, previousEvents, tcso1, 1, expectedIndex,
            new TrivialClusterScaleEvent("vm1", "host1", clusterId1, "routeKey", null, true, Long.MAX_VALUE),
            new TrivialClusterScaleEvent("vm2", "host2", clusterId1, "routeKey", null, true, Long.MAX_VALUE-1),
            new TrivialClusterScaleEvent("vm3", "host3", clusterId1, "routeKey", null, true, Long.MAX_VALUE-2));
      /* In the case of identical timestamps, the last one added is the one retained */
      expectedIndex = 2;
      testEventOrdering(clusterId2, previousEvents, tcso2, 1, expectedIndex,
            new TrivialClusterScaleEvent("vm1", "host1", clusterId2, "routeKey", null, true, Long.MAX_VALUE),
            new TrivialClusterScaleEvent("vm2", "host2", clusterId2, "routeKey", null, true, Long.MAX_VALUE),
            new TrivialClusterScaleEvent("vm3", "host3", clusterId2, "routeKey", null, true, Long.MAX_VALUE));

      assertEquals(clusterId0, tcso0.getClusterId());
      assertNotNull(tcso0.getContext());
      assertNotNull(tcso0.getThreadLocalCompoundStatus());
   }

   private class WaitResult {
      ClusterScaleCompletionEvent _testNotFinished;
      ClusterScaleCompletionEvent _testFinished;
      String _routeKeyReported;
   }

   @Test
   /* Two different clusters are scaled concurrently */
   public void testInvokeConcurrentScaleStrategy() throws InterruptedException {
      int numClusters = 3;
      populateSimpleClusterMap(numClusters, 4, false);    /* Blocks until CSCL has generated all events */
      assertTrue(waitForTargetClusterCount(3, 1000));

      Iterator<String> i = _clusterNames.iterator();
      final String clusterId1 = deriveClusterIdFromClusterName(i.next());
      final String clusterId2 = deriveClusterIdFromClusterName(i.next());
      final String clusterId3 = deriveClusterIdFromClusterName(i.next());

      /* Creating a new cluster with a non manual scale strategy will produce an initial ClusterScaleEvent */
      final Set<ClusterScaleCompletionEvent> completionEventsFromInit1 = waitForClusterScaleCompletionEvents(clusterId1, 1, 1000);
      final Set<ClusterScaleCompletionEvent> completionEventsFromInit2 = waitForClusterScaleCompletionEvents(clusterId2, 1, 1000);
      final Set<ClusterScaleCompletionEvent> completionEventsFromInit3 = waitForClusterScaleCompletionEvents(clusterId3, 1, 1000);
      assertNotNull(completionEventsFromInit1);
      assertNotNull(completionEventsFromInit2);
      assertNotNull(completionEventsFromInit3);

      final int cluster1delay = 3000;
      final int cluster2delay = 2000;
      final int cluster3delay = 1000;

      final String routeKey1 = "route1";
      final String routeKey2 = "route2";
      final String routeKey3 = "route3";

      /* Create ClusterScaleOperations, which control how a cluster is scaled - each operation gets a different delay to its "scaling" */
      TrivialClusterScaleOperation tcso1 = _trivialScaleStrategy.new TrivialClusterScaleOperation(cluster1delay);
      TrivialClusterScaleOperation tcso2 = _trivialScaleStrategy.new TrivialClusterScaleOperation(cluster2delay);
      TrivialClusterScaleOperation tcso3 = _trivialScaleStrategy.new TrivialClusterScaleOperation(cluster3delay);

      /* Add the test ClusterScaleOperation to the test scale strategy, which is a singleton */
      _trivialScaleStrategy.setClusterScaleOperation(clusterId1, tcso1);
      _trivialScaleStrategy.setClusterScaleOperation(clusterId2, tcso2);
      _trivialScaleStrategy.setClusterScaleOperation(clusterId3, tcso3);

      final WaitResult waitResult1 = new WaitResult();
      final WaitResult waitResult2 = new WaitResult();
      final WaitResult waitResult3 = new WaitResult();

      /* Each event has a channel it can report completion back on, along with a routeKey */
      ChannelReporter reporter1 = new ChannelReporter() {
         @Override
         public void reportBack(String routeKey) {
            waitResult1._routeKeyReported = routeKey;
         }
      };
      ChannelReporter reporter2 = new ChannelReporter() {
         @Override
         public void reportBack(String routeKey) {
            waitResult2._routeKeyReported = routeKey;
         }
      };
      ChannelReporter reporter3 = new ChannelReporter() {
         @Override
         public void reportBack(String routeKey) {
            waitResult3._routeKeyReported = routeKey;
         }
      };

      /* Simulate cluster scale events being triggered from an EventProducer */
      _eventConsumer.placeEventOnQueue(new TrivialClusterScaleEvent(null, null, clusterId1, routeKey1, reporter1, false));
      final long cluster1finishedAtTime = System.currentTimeMillis() + cluster1delay;
      _eventConsumer.placeEventOnQueue(new TrivialClusterScaleEvent(null, null, clusterId2, routeKey2, reporter2, false));
      final long cluster2finishedAtTime = System.currentTimeMillis() + cluster2delay;
      _eventConsumer.placeEventOnQueue(new TrivialClusterScaleEvent(null, null, clusterId3, routeKey3, reporter3, false));
      final long cluster3finishedAtTime = System.currentTimeMillis() + cluster3delay;

      /* Three threads concurrently wait for the response from the 3 clusters and check that the response is neither early nor significantly late */
      new Thread(new Runnable(){
         @Override
         public void run() {
            int delayTillCluster1finished = (int)(cluster1finishedAtTime - System.currentTimeMillis());
            waitResult1._testNotFinished = waitForClusterScaleCompletionEvent(clusterId1, delayTillCluster1finished-500, completionEventsFromInit1);
            waitResult1._testFinished = waitForClusterScaleCompletionEvent(clusterId1, 1500, completionEventsFromInit1);
      }}).start();

      new Thread(new Runnable(){
         @Override
         public void run() {
            int delayTillCluster2finished = (int)(cluster2finishedAtTime - System.currentTimeMillis());
            waitResult2._testNotFinished = waitForClusterScaleCompletionEvent(clusterId2, delayTillCluster2finished-500, completionEventsFromInit2);
            waitResult2._testFinished = waitForClusterScaleCompletionEvent(clusterId2, 1500, completionEventsFromInit2);
      }}).start();

      new Thread(new Runnable(){
         @Override
         public void run() {
            int delayTillCluster3finished = (int)(cluster3finishedAtTime - System.currentTimeMillis());
            waitResult3._testNotFinished = waitForClusterScaleCompletionEvent(clusterId3, delayTillCluster3finished-500, completionEventsFromInit3);
            waitResult3._testFinished = waitForClusterScaleCompletionEvent(clusterId3, 1500, completionEventsFromInit3);
      }}).start();

      /* Wait for the above threads to return the results */
      Thread.sleep(cluster1delay + 2000);

      /* Check that the operations completed concurrently and blocked at the correct times */
      assertNull(waitResult1._testNotFinished);
      assertNotNull(waitResult1._testFinished);
      assertEquals(routeKey1, waitResult1._routeKeyReported);

      assertNull(waitResult2._testNotFinished);
      assertNotNull(waitResult2._testFinished);
      assertEquals(routeKey2, waitResult2._routeKeyReported);

      assertNull(waitResult3._testNotFinished);
      assertNotNull(waitResult3._testFinished);
      assertEquals(routeKey3, waitResult3._routeKeyReported);
   }

   @Test
   /* Ensure that the same cluster cannot be concurrently scaled */
   public void negativeTestConcurrentSameCluster() throws InterruptedException {
      int numClusters = 3;
      populateSimpleClusterMap(numClusters, 4, false);    /* Blocks until CSCL has generated all events */
      assertTrue(waitForTargetClusterCount(3, 1000));

      int delayMillis = 3000;
      String clusterId = deriveClusterIdFromClusterName(_clusterNames.iterator().next());
      TrivialClusterScaleOperation tcso = _trivialScaleStrategy.new TrivialClusterScaleOperation(delayMillis);
      _trivialScaleStrategy.setClusterScaleOperation(clusterId, tcso);

      final Set<ClusterScaleCompletionEvent> completionEventsFromInit = waitForClusterScaleCompletionEvents(clusterId, 1, 1000);
      assertNotNull(completionEventsFromInit);

      _eventConsumer.placeEventOnQueue(new TrivialClusterScaleEvent(clusterId, false));
      Thread.sleep(1000);
      _eventConsumer.placeEventOnQueue(new TrivialClusterScaleEvent(clusterId, false));
      Thread.sleep(1000);
      _eventConsumer.placeEventOnQueue(new TrivialClusterScaleEvent(clusterId, false));

      /* Expectation here is that 1st event will trigger a scale. The second one arrives and third one arrives and are both queued back up */
      /* Then, the second and third ones are processed together in a single invocation */

      /* This call should time out - there should only be one that's been processed so far... */
      Set<ClusterScaleCompletionEvent> results1 = waitForClusterScaleCompletionEvents(clusterId, 2, 4000, completionEventsFromInit);
      assertEquals(1, results1.size());

      /* The two extra events should have been picked up and should result in a second consolidated invocation. This should not time out. */
      Set<ClusterScaleCompletionEvent> results2 = waitForClusterScaleCompletionEvents(clusterId, 2, 4000, completionEventsFromInit);
      assertEquals(2, results2.size());
   }

   private class ReportResult {
      String _routeKey;
      byte[] _data;

      VHMJsonReturnMessage getMessage() {
         Gson gson = new Gson();
         return gson.fromJson(new String(_data), VHMJsonReturnMessage.class);
      }
   }

   private void simulateVcExtraInfoChange(String clusterName, Boolean isAuto, Integer minInstances) {
      String masterVmName1 = getMasterVmNameForCluster(clusterName);
      VMEventData switchEvent = createEventData(clusterName,
            masterVmName1, true, null, null, masterVmName1, isAuto, minInstances, -1, false);
      processNewEventData(switchEvent);
   }

   /* Checks what happens if Serengeti sends a blocking call to put a cluster into manual mode, when the cluster is not currently scaling */
   @Test
   public void testSwitchToManualFromOtherNotScaling() throws InterruptedException {
      int numClusters = 3;
      populateSimpleClusterMap(numClusters, 4, false);    /* Blocks until CSCL has generated all events */
      assertTrue(waitForTargetClusterCount(3, 1000));

      Iterator<String> i = _clusterNames.iterator();
      String clusterName1 = i.next();
      final ReportResult serengetiQueueResult = new ReportResult();
      String routeKey1 = "routeKey1";
      String clusterId1 = deriveClusterIdFromClusterName(clusterName1);

      String clusterName2 = i.next();
      String routeKey2 = "routeKey2";
      String clusterId2 = deriveClusterIdFromClusterName(clusterName2);

      /* Creating a new cluster with a non manual scale strategy will produce an initial ClusterScaleEvent */
      final Set<ClusterScaleCompletionEvent> completionEventsFromInit1 = waitForClusterScaleCompletionEvents(clusterId1, 1, 1000);
      final Set<ClusterScaleCompletionEvent> completionEventsFromInit2 = waitForClusterScaleCompletionEvents(clusterId2, 1, 1000);
      assertNotNull(completionEventsFromInit1);
      assertNotNull(completionEventsFromInit2);

      TestRabbitConnection testConnection = setUpClusterForSwitchTest(clusterName1, 0, serengetiQueueResult);

      /* Serengeti limit event is a blocking instruction to switch to Manual Scale Strategy */
      SerengetiLimitInstruction limitEvent1 = new SerengetiLimitInstruction(
            clusterName1,
            SerengetiLimitAction.actionWaitForManual, 0,
            new RabbitConnectionCallback(routeKey1, testConnection));

      /* Send the manual event on the message queue */
      _eventConsumer.placeEventOnQueue(limitEvent1);

      /* Wait for a period to allow the Serengeti event to be picked up */
      Thread.sleep(2000);

      /* The scale strategy switch should not be processed until CSCL has picked up the extraInfo change however VHM responds immediately
       * with a progress message so that Serengeti knows the message has been received. At this point, we haven't yet put this CSCL change through... */
      assertNotNull(serengetiQueueResult._data);
      assertEquals(0, serengetiQueueResult.getMessage().progress);
      assertFalse(serengetiQueueResult.getMessage().finished);

      /* Even though CSCL hasn't yet changed state, the cluster should not respond to scale events - expect time-out */
      _eventConsumer.placeEventOnQueue(new TrivialClusterScaleEvent(null, null, clusterId1, routeKey1, null, false));
      assertNull(waitForClusterScaleCompletionEvent(clusterId1, 2000, completionEventsFromInit1));

      /* Create a simulated VC event, changing extraInfo to the manual strategy for cluster1 */
      simulateVcExtraInfoChange(clusterName1, false, null);
      /* Give it some time to be processed and then check it reported back */
      Thread.sleep(2000);

      assertNotNull(serengetiQueueResult._data);
      assertEquals(serengetiQueueResult._routeKey, routeKey1);

      /* Do a second test where the CSCL update occurs before the manual limit event arrives */
      serengetiQueueResult._data = null;

      /* Create a simulated VC event, changing extraInfo to the manual strategy for cluster2 */
      simulateVcExtraInfoChange(clusterName2, false, null);

      /* Give it some time to be processed */
      Thread.sleep(2000);

      /* Verify this scale event should be ignored, since the manual scale strategy
         should not respond to these events - wait should time out */
      _eventConsumer.placeEventOnQueue(new TrivialClusterScaleEvent(null, null, clusterId2, routeKey2, null, false));
      assertNull(waitForClusterScaleCompletionEvent(clusterId2, 2000, completionEventsFromInit2));

      /* Verify nothing yet reported on the Serengeti queue - blocking behavior */
      assertNull(serengetiQueueResult._data);

      SerengetiLimitInstruction limitEvent2 = new SerengetiLimitInstruction(
            getFolderNameForClusterName(clusterName2),
            SerengetiLimitAction.actionWaitForManual, 0,
            new RabbitConnectionCallback(routeKey2, testConnection));

      /* Verify that the scale strategy is now manual */
      ClusterMap clusterMap = null;
      try {
         clusterMap = getAndReadLockClusterMap();
         assertEquals(ManualScaleStrategy.MANUAL_SCALE_STRATEGY_KEY, clusterMap.getScaleStrategyKey(clusterId2));
      } finally {
         unlockClusterMap(clusterMap);
      }

      /* Serengeti limit event arrives */
      _eventConsumer.placeEventOnQueue(limitEvent2);

      /* Give it some time to be processed and then check it reported back */
      Thread.sleep(2000);
      assertNotNull(serengetiQueueResult._data);
      assertEquals(serengetiQueueResult._routeKey, routeKey2);
   }

   private TestRabbitConnection setUpClusterForSwitchTest(String clusterName, int scaleDelayMillis, final ReportResult result) {
      String clusterId = deriveClusterIdFromClusterName(clusterName);

      /* Create a ClusterScaleOperation, which controls how a cluster is scaled */
      TrivialClusterScaleOperation tcso = _trivialScaleStrategy.new TrivialClusterScaleOperation(scaleDelayMillis);

      /* Add the test ClusterScaleOperation to the test scale strategy */
      _trivialScaleStrategy.setClusterScaleOperation(clusterId, tcso);

      /* Set up the Rabbit Infrastructure simulating the Serengeti Queue */
      TestRabbitConnection testConnection = new TestRabbitConnection(new TestRabbitConnection.TestChannel() {
         @Override
         public void basicPublish(String localRouteKey, byte[] data) {
            result._routeKey = localRouteKey;
            result._data = data;
         }
      });

      /* Check that the existing scale strategy is not manual */
      ClusterMap clusterMap = null;
      try {
         clusterMap = getAndReadLockClusterMap();
         assertEquals(_trivialScaleStrategy._testKey, clusterMap.getScaleStrategyKey(clusterId));
      } finally {
         unlockClusterMap(clusterMap);
      }

      return testConnection;
   }

   @Test
   public void testSwitchToManualFromOtherScaling() throws InterruptedException {
      int numClusters = 3;
      populateSimpleClusterMap(numClusters, 4, false);    /* Blocks until CSCL has generated all events */
      assertTrue(waitForTargetClusterCount(3, 1000));

      Iterator<String> i = _clusterNames.iterator();
      String clusterName1 = i.next();
      final ReportResult serengetiQueueResult = new ReportResult();
      String routeKey1 = "routeKey1";
      String clusterId1 = deriveClusterIdFromClusterName(clusterName1);

      /* Creating a new cluster with a non manual scale strategy will produce an initial ClusterScaleEvent */
      final Set<ClusterScaleCompletionEvent> completionEventsFromInit1 = waitForClusterScaleCompletionEvents(clusterId1, 1, 1000);
      assertNotNull(completionEventsFromInit1);

      int scaleDelayMillis = 8000;
      TestRabbitConnection testConnection = setUpClusterForSwitchTest(clusterName1, scaleDelayMillis, serengetiQueueResult);

      /* Serengeti limit event is a blocking instruction to switch to Manual Scale Strategy */
      SerengetiLimitInstruction limitEvent1 = new SerengetiLimitInstruction(
            getFolderNameForClusterName(clusterName1),
            SerengetiLimitAction.actionWaitForManual, 0,
            new RabbitConnectionCallback(routeKey1, testConnection));

      /* Simulate a cluster scale event being triggered from an EventProducer.
       * The delayMillis will ensure that it takes a while to complete the scale */
      _eventConsumer.placeEventOnQueue(new TrivialClusterScaleEvent(clusterId1, false));

      /* Wait long enough for the scale event to start, but not to have completed */
      int waitForStartMillis = 2000;
      Thread.sleep(waitForStartMillis);

      /* Send the manual switch event on the message queue */
      _eventConsumer.placeEventOnQueue(limitEvent1);

      /* This should time out, showing that the manual switch is correctly being blocked */
      int waitForLimitInstructionMillis = 2000;
      assertNull(waitForClusterScaleCompletionEvent(clusterId1, waitForLimitInstructionMillis, completionEventsFromInit1));

      /* Create a simulated VC event, changing extraInfo to the manual strategy */
      simulateVcExtraInfoChange(clusterName1, false, null);

      /* By this point, the manual switch should have completed */
      int waitForManualSwitch = (scaleDelayMillis - (waitForStartMillis + waitForLimitInstructionMillis)) + 2000;
      ClusterScaleCompletionEvent latestEvent = waitForClusterScaleCompletionEvent(clusterId1, waitForManualSwitch, completionEventsFromInit1);
      assertNotNull(latestEvent);
      completionEventsFromInit1.add(latestEvent);        /* Ignore this event in the next wait */

      /* Ensure thread has had enough time to report back on Serengeti queue */
      Thread.sleep(2000);

      assertNotNull(serengetiQueueResult._data);
      assertEquals(serengetiQueueResult._routeKey, routeKey1);

      /* Verify that the scale strategy is now manual */
      ClusterMap clusterMap = null;
      try {
         clusterMap = getAndReadLockClusterMap();
         assertEquals(ManualScaleStrategy.MANUAL_SCALE_STRATEGY_KEY, clusterMap.getScaleStrategyKey(clusterId1));
      } finally {
         unlockClusterMap(clusterMap);
      }

      /* Now switch to automatic and assert that this invokes the scale strategy */
      simulateVcExtraInfoChange(clusterName1, true, 2);

      /* Verify that the scale operation blocks as expected */
      assertNull(waitForClusterScaleCompletionEvent(clusterId1, 2000, completionEventsFromInit1));

      /* Verify that it completes successfully */
      latestEvent = waitForClusterScaleCompletionEvent(clusterId1, scaleDelayMillis, completionEventsFromInit1);
      assertNotNull(latestEvent);
   }

   private Set<ClusterScaleCompletionEvent> getCompletionEventsFromInit() {
      Set<ClusterScaleCompletionEvent> result = new HashSet<ClusterScaleCompletionEvent>();
      for (String clusterName : _clusterNames) {
         result.addAll(waitForClusterScaleCompletionEvents(deriveClusterIdFromClusterName(clusterName), 1, 2000));
      }
      return result;
   }

   @Test
   public void testImpliedScaleEvent() {
      int numClusters = 3;
      populateSimpleClusterMap(numClusters, 4, false);    /* Blocks until CSCL has generated all events */
      assertTrue(waitForTargetClusterCount(3, 1000));
      Set<ClusterScaleCompletionEvent> completionEventsFromInit = getCompletionEventsFromInit();

      /* Check that getImpliedScaleEventsForUpdate has been invoked for the cluster creation */
      assertEquals(3, _isNewClusterResult.size());
      for (int i=0; i<numClusters; i++) {
         assertEquals(true, _isNewClusterResult.get(i));
      }
      _isNewClusterResult.clear();

      String clusterName = _clusterNames.iterator().next();
      String clusterId = deriveClusterIdFromClusterName(clusterName);
      /* Create a ClusterScaleOperation, which controls how a cluster is scaled */
      TrivialClusterScaleOperation tcso = _trivialScaleStrategy.new TrivialClusterScaleOperation();
      /* Add the test ClusterScaleOperation to the test scale strategy */
      _trivialScaleStrategy.setClusterScaleOperation(clusterId, tcso);

      /* Simulate a CSCL update event that produces a subsequent implied scale event */
      /* It's important that the scale strategy isn't switched, so enableAutomation=true */
      simulateVcExtraInfoChange(clusterName, true, 2);

      /* Wait for VHM to respond, having invoked the ScaleStrategy */
      assertNotNull(waitForClusterScaleCompletionEvent(clusterId, 2000, completionEventsFromInit));
      assertEquals(clusterId, tcso.getClusterId());
      assertEquals(1, _isNewClusterResult.size());
      assertEquals(false, _isNewClusterResult.get(0));
   }

   @Test
   public void testRequeueEvents() {
      int numClusters = 3;
      populateSimpleClusterMap(numClusters, 4, false);    /* Blocks until CSCL has generated all events */
      assertTrue(waitForTargetClusterCount(3, 1000));

      String clusterId = deriveClusterIdFromClusterName(_clusterNames.iterator().next());
      /* Create a ClusterScaleOperation, which controls how a cluster is scaled */
      TrivialClusterScaleOperation tcso = _trivialScaleStrategy.new TrivialClusterScaleOperation();
      /* Add the test ClusterScaleOperation to the test scale strategy */
      _trivialScaleStrategy.setClusterScaleOperation(clusterId, tcso);

      /* Setup the scaleStrategy so that it re-queues the TrivialClusterScaleEvent a certain number of times */
      int timesToRequeue = 4;
      tcso.setRequeueEventTimes(timesToRequeue);

      /* Simulate a cluster scale event being triggered from an EventProducer */
      _eventConsumer.placeEventOnQueue(new TrivialClusterScaleEvent(clusterId, false));

      /* VHM should invoke the scale strategy as many times as the event is queued */
      Set<ClusterScaleCompletionEvent> foundEvents = new HashSet<ClusterScaleCompletionEvent>();
      for (int i=0; i<(timesToRequeue+1); i++) {
         ClusterScaleCompletionEvent event = waitForClusterScaleCompletionEvent(clusterId, 2000, foundEvents);
         assertNotNull(event);
         foundEvents.add(event);
      }
      /* Check that it is not queued for more times than requested */
      assertNull(waitForClusterScaleCompletionEvent(clusterId, 2000, foundEvents));
   }

   @Override
   public void registerEventConsumer(EventConsumer eventConsumer) {
      _eventConsumer = eventConsumer;
   }

   @Override
   public void stop() {
      /* No-op */
   }

   @Override
   /* Gets a read lock on ClusterMap - call unlock when done */
   public ClusterMap getAndReadLockClusterMap() {
      return _clusterMapAccess.lockClusterMap();
   }

   @Override
   public void unlockClusterMap(final ClusterMap clusterMap) {
      _clusterMapAccess.unlockClusterMap(clusterMap);
   }

   @Override
   public void start(EventProducerStartStopCallback callback) {
      /* No-op */
   }

   @Override
   public boolean isStopped() {
      return false;
   }
}
