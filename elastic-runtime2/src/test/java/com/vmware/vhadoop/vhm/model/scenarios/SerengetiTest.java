package com.vmware.vhadoop.vhm.model.scenarios;

import static com.vmware.vhadoop.vhm.model.vcenter.VirtualCenter.Metric.GRANTED;
import static com.vmware.vhadoop.vhm.model.vcenter.VirtualCenter.Metric.READY;
import static com.vmware.vhadoop.vhm.model.vcenter.VirtualCenter.Metric.USAGE;
import static org.junit.Assert.assertEquals;

import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.junit.Test;

import com.vmware.vhadoop.util.LogFormatter;
import com.vmware.vhadoop.vhm.AbstractSerengetiTestBase;
import com.vmware.vhadoop.vhm.model.Allocation;
import com.vmware.vhadoop.vhm.model.api.ResourceType;
import com.vmware.vhadoop.vhm.model.scenarios.Serengeti.Compute;
import com.vmware.vhadoop.vhm.model.scenarios.Serengeti.Master;
import com.vmware.vhadoop.vhm.model.vcenter.VirtualCenter.Metric;
import com.vmware.vhadoop.vhm.model.workloads.EndlessTaskGreedyJob;
import com.vmware.vhadoop.vhm.model.workloads.HadoopJob;

public class SerengetiTest extends AbstractSerengetiTestBase
{
   private static Logger _log = Logger.getLogger(SerengetiTest.class.getName());

   public SerengetiTest() {
      Logger.getLogger("").setLevel(Level.FINER);
      Handler handler = Logger.getLogger("").getHandlers()[0];
      handler.setLevel(Level.FINER);
      handler.setFormatter(new LogFormatter());
   }

   private void logMetrics(Compute nodes[]) {
      for (Compute node : nodes) {
         if (!node.powerState()) {
            continue;
         }

         long metrics[] = _vCenter.getMetrics(node.name());
         StringBuilder sb = new StringBuilder(node.name());
         for (Metric metric : new Metric[] {USAGE, READY, GRANTED}) {
            sb.append("\t").append(metric).append(": ").append(metrics[metric.ordinal()]);
         }

         _log.info(sb.toString());
      }
   }

   @Test
   public void testJobDeploysAfterHardPowerCycle() {
      final int numberOfHosts = 2;
      final int computeNodesPerHost = 2;
      String clusterName = "serengetiTest";

      Allocation hostCapacity = Allocation.zeroed();
      hostCapacity.set(ResourceType.CPU, 4000);
      hostCapacity.set(ResourceType.MEMORY, 24000);

      /* general test setup */
      setup(numberOfHosts, hostCapacity);
      _vCenter.setMetricsInterval(500);
      _serengeti.setMaxLatency(500);

      /* create a cluster to work with */
      Master cluster = createCluster(clusterName, computeNodesPerHost);

      /* power on all the nodes and enable them in serengeti */
      Compute nodes[] = cluster.getComputeNodes().toArray(new Compute[0]);

      for (Compute node : nodes) {
         _log.info("Powering on node "+node.name());
         node.powerOn();
         cluster.enable(node.getHostname());
      }

      /* hard cycle vms */
      for (int i = 0; i < nodes.length; i++) {
         nodes[i].powerOff();
      }
      for (int i = 0; i < nodes.length; i++) {
         nodes[i].powerOn();
      }

      Allocation footprint = Allocation.zeroed();
      footprint.set(ResourceType.CPU, 4000);
      footprint.set(ResourceType.MEMORY, 2000);
      HadoopJob job = new EndlessTaskGreedyJob("greedyJob", numberOfHosts, footprint);

      /* start a job that should roll out to all of the nodes as they power on */
      cluster.execute(job);

      /* wait for the serengeti max latency and stats interval to expire */
      long delay = Math.max(2 * _vCenter.getMetricsInterval(), _serengeti.getMaxLatency());
      try {
         Thread.sleep(delay);
      } catch (InterruptedException e) {}

      logMetrics(nodes);
      for (Compute node : nodes) {
         assertEquals("cpu ready value is not correct for "+node.name(), 2000, _vCenter.getRawMetric(node.name(), READY).longValue());
      }
   }

   @Test
   public void test() {
      final int numberOfHosts = 2;
      final int computeNodesPerHost = 2;
      String clusterName = "serengetiTest";

      Allocation hostCapacity = Allocation.zeroed();
      hostCapacity.set(ResourceType.CPU, 4000);
      hostCapacity.set(ResourceType.MEMORY, 24000);

      /* general test setup */
      setup(numberOfHosts, hostCapacity);
      _vCenter.setMetricsInterval(500);
      _serengeti.setMaxLatency(500);

      /* create a cluster to work with */
      Master cluster = createCluster(clusterName, computeNodesPerHost);

      /* power on all the nodes and enable them in serengeti */
      Compute nodes[] = cluster.getComputeNodes().toArray(new Compute[0]);

      Allocation footprint = Allocation.zeroed();
      footprint.set(ResourceType.CPU, 4000);
      footprint.set(ResourceType.MEMORY, 2000);
      HadoopJob job = new EndlessTaskGreedyJob("greedyJob", numberOfHosts, footprint);

      cluster.execute(job);

      logMetrics(nodes);

      for (Compute node : nodes) {
         _log.info("Powering on node "+node.name());
         node.powerOn();
         cluster.enable(node.getHostname());

         logMetrics(nodes);
      }

      /* wait for the serengeti max latency and stats interval to expire */
      long delay = Math.max(_vCenter.getMetricsInterval(), _serengeti.getMaxLatency());
      try {
         Thread.sleep(delay);
      } catch (InterruptedException e) {}

      logMetrics(nodes);
      for (Compute node : nodes) {
         assertEquals("cpu ready value is not correct for "+node.name(), 2000, _vCenter.getRawMetric(node.name(), READY).longValue());
      }
   }

   @Test
   public void testRollingPowerOnJobDeploys() {
      final int numberOfHosts = 1;
      final int computeNodesPerHost = 8;
      String clusterName = "serengetiTest";

      Allocation hostCapacity = Allocation.zeroed();
      hostCapacity.set(ResourceType.CPU, 32000);
      hostCapacity.set(ResourceType.MEMORY, 24000);

      /* general test setup */
      setup(numberOfHosts, hostCapacity);
      _vCenter.setMetricsInterval(500);
      _serengeti.setMaxLatency(500);

      /* create a cluster to work with */
      Master cluster = createCluster(clusterName, computeNodesPerHost);

      /* power on all the nodes and enable them in serengeti */
      Compute nodes[] = cluster.getComputeNodes().toArray(new Compute[0]);

      Allocation footprint = Allocation.zeroed();
      footprint.set(ResourceType.CPU, 4000);
      footprint.set(ResourceType.MEMORY, 2000);
      HadoopJob job = new EndlessTaskGreedyJob("greedyJob", numberOfHosts, footprint);

      cluster.execute(job);

      logMetrics(nodes);

      /* rolling power on of the nodes, via target compute node num and expect not to need to explicitly enable the nodes here */
      for (int target = 1; target <= cluster.availableComputeNodes(); target++) {
         cluster.setTargetComputeNodeNum(target);

         setTimeout(5000);
         assertVMsInPowerState("waiting for target compute node num ("+target+") to take effect", cluster, target, true);

         /* retry check until max latency has expired */
         logMetrics(nodes);
         int processingTasks;
         int poweredOnNodes;

         do {
            /* wait for the stats interval to expire */
            try {
               Thread.sleep(_vCenter.getMetricsInterval());
            } catch (InterruptedException e) {}

            processingTasks = job.numberOfTasks(HadoopJob.Stage.PROCESSING);
            poweredOnNodes = cluster.numberComputeNodesInPowerState(true);
         } while (timeout() >= 0 && processingTasks != poweredOnNodes);

         assertEquals("number of hadoop tasks does not match powered on nodes", poweredOnNodes, processingTasks);
      }

      logMetrics(nodes);
   }
}