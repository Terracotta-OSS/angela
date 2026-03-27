/*
 * Copyright Terracotta, Inc.
 * Copyright IBM Corp. 2024, 2025
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.terracotta.angela.agent.com;

import org.awaitility.Awaitility;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.terracotta.angela.agent.Agent;
import org.terracotta.angela.common.cluster.AtomicCounter;
import org.terracotta.angela.common.cluster.Barrier;
import org.terracotta.angela.common.distribution.Distribution;
import org.terracotta.angela.common.net.DefaultPortAllocator;
import org.terracotta.angela.common.net.PortAllocator;
import org.terracotta.angela.common.topology.InstanceId;
import org.terracotta.angela.common.util.IpUtils;
import org.zeroturnaround.process.PidUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.Arrays.asList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.terracotta.angela.common.distribution.Distribution.distribution;
import static org.terracotta.angela.common.topology.LicenseType.TERRACOTTA_OS;
import static org.terracotta.angela.common.topology.PackageType.KIT;
import static org.terracotta.angela.common.topology.Version.version;

/**
 * Integration tests for {@link org.terracotta.angela.agent.com.grid.hazelcast.HazelcastExecutor}
 * (local mode), mirroring {@link IgniteLocalExecutorIT}.
 */
public class HazelcastLocalExecutorIT {

  UUID group = UUID.randomUUID();
  transient PortAllocator portAllocator = new DefaultPortAllocator();
  transient Agent agent = Agent.hazelcastOrchestrator(group, portAllocator);
  AgentID agentID = agent.getAgentID();
  transient Executor executor = agent.getGridProvider().createExecutor(agent.getGroupId(), agent.getAgentID());

  @Before
  public void setUp() {
    counter.set(0);
  }

  @After
  public void tearDown() {
    executor.close();
    agent.close();
    portAllocator.close();
  }

  @Test
  public void testGetLocalAgentID() {
    assertEquals(Agent.AGENT_TYPE_ORCHESTRATOR + "#" + PidUtil.getMyPid() + "@" + IpUtils.getHostName() + "#" + agentID.getPort(), executor.getLocalAgentID().toString());
  }

  @Test
  public void testFindAgentID() {
    // local addresses
    assertTrue(executor.findAgentID(IpUtils.getHostName()).isPresent());
    assertTrue(executor.findAgentID("localhost").isPresent());

    // a remote host (no remote agent spawned yet)
    assertFalse(executor.findAgentID("foo").isPresent());

    assertEquals(agentID, executor.findAgentID(IpUtils.getHostName()).get());
    assertEquals(agentID, executor.findAgentID("localhost").get());
  }

  @Test
  public void testStartRemoteAgent() {
    // in local mode, startRemoteAgent just registers the hostname against the local agent
    assertEquals(1, executor.getGroup().size());
    assertFalse(executor.findAgentID("foo").isPresent());
    assertFalse(executor.startRemoteAgent("foo").isPresent());
    assertTrue(executor.findAgentID("foo").isPresent());
    assertEquals(agentID, executor.findAgentID("foo").get());
    assertEquals(1, executor.getGroup().size());
  }

  @Test
  public void testGetGroup() {
    AgentGroup group = executor.getGroup();
    System.out.println(group);
    assertEquals(this.group, group.getId());
    assertEquals(1, group.size());
    assertEquals(agentID, group.getAllAgents().iterator().next());

    // simulate another agent (for a client job)
    try (Agent client = Agent.hazelcast(agent.getGroupId(), "client-1", portAllocator, group.getPeerAddresses())) {
      // membership events are async — wait until the second member is visible
      Awaitility.await().atMost(10, TimeUnit.SECONDS)
          .until(() -> executor.getGroup().size() == 2);

      group = executor.getGroup();
      System.out.println(group);
      assertEquals(this.group, group.getId());
      assertEquals(2, group.size());
      assertTrue(group.contains(client.getAgentID()));
    }
  }

  @Test
  public void testGetCluster() {
    final AtomicCounter counter = executor.getCluster().atomicCounter("c", 0);
    try (Agent agent2 = Agent.hazelcast(agent.getGroupId(), "client-1", portAllocator, executor.getGroup().getPeerAddresses());
         Executor executor2 = agent2.getGridProvider().createExecutor(agent2.getGroupId(), agent2.getAgentID())) {
      final AtomicCounter counter2 = executor2.getCluster().atomicCounter("c", 0);
      assertEquals(1, counter.incrementAndGet());
      counter2.incrementAndGet();
      assertEquals(2, counter.getAndIncrement());
      assertEquals(3, counter2.get());
    }
  }

  @SuppressWarnings("Convert2MethodRef")
  @Test
  public void testExecute() throws ExecutionException, InterruptedException {
    try (Agent agent2 = Agent.hazelcast(agent.getGroupId(), "client-1", portAllocator, executor.getGroup().getPeerAddresses());
         Executor executor2 = agent2.getGridProvider().createExecutor(agent2.getGroupId(), agent2.getAgentID())) {
      // wait for both sides to see each other
      Awaitility.await().atMost(10, TimeUnit.SECONDS)
          .until(() -> executor.getGroup().size() == 2 && executor2.getGroup().size() == 2);

      executor.execute(agentID, (RemoteRunnable) () -> counter.incrementAndGet());
      executor.execute(agent2.getAgentID(), (RemoteRunnable) () -> counter.incrementAndGet());
      assertEquals(2, counter.get());

      executor2.executeAsync(agentID, (RemoteRunnable) () -> counter.incrementAndGet()).get();
      executor2.executeAsync(agent2.getAgentID(), (RemoteRunnable) () -> counter.incrementAndGet()).get();
      assertEquals(4, counter.get());

      assertEquals(5, executor.execute(agent2.getAgentID(), (RemoteCallable<? extends Object>) () -> counter.incrementAndGet()));
      assertEquals(6, executor2.executeAsync(agentID, (RemoteCallable<? extends Object>) () -> counter.incrementAndGet()).get());
    }
  }

  @Test
  public void testUploadFiles() throws IOException {
    initFiles();

    InstanceId instanceId = new InstanceId("foo", "client");
    executor.uploadFiles(instanceId, asList(Paths.get("target", "one.txt"), Paths.get("target", "files")), CompletableFuture.completedFuture(null));
    assertEquals(4, executor.getFileTransferQueue(instanceId).size());
  }

  @Test
  public void testDownloadFiles() throws IOException {
    if (Files.exists(Paths.get("target", "download"))) {
      org.terracotta.utilities.io.Files.deleteTree(Paths.get("target", "download"));
    }

    testUploadFiles();

    InstanceId instanceId = new InstanceId("foo", "client");
    executor.downloadFiles(instanceId, Paths.get("target", "download"));
    assertEquals(0, executor.getFileTransferQueue(instanceId).size());
    assertTrue(Files.exists(Paths.get("target/download/one.txt")));
    assertTrue(Files.exists(Paths.get("target/download/files/two.txt")));
    assertTrue(Files.exists(Paths.get("target/download/files/sub/three.txt")));
  }

  @Test
  public void testUploadClientJars() throws IOException {
    initFiles();

    InstanceId instanceId = new InstanceId(UUID.randomUUID().toString(), "client");
    assertEquals(0, executor.getFileTransferQueue(instanceId).size());

    try (Agent agent2 = Agent.hazelcast(agent.getGroupId(), "client-1", portAllocator, executor.getGroup().getPeerAddresses())) {
      Awaitility.await().atMost(10, TimeUnit.SECONDS)
          .until(() -> executor.getGroup().size() == 2);

      executor.uploadClientJars(agent2.getAgentID(), instanceId, asList(Paths.get("target", "one.txt"), Paths.get("target", "files")));
    }

    assertEquals(0, executor.getFileTransferQueue(instanceId).size());
    assertTrue(Files.exists(Paths.get("target/angela/work/" + instanceId + "/lib/one.txt")));
    assertTrue(Files.exists(Paths.get("target/angela/work/" + instanceId + "/lib/files/two.txt")));
    assertTrue(Files.exists(Paths.get("target/angela/work/" + instanceId + "/lib/files/sub/three.txt")));
  }

  @Test
  public void testUploadKit() throws IOException {
    if (Files.exists(Paths.get("target/angela/kits/3.9.9"))) {
      org.terracotta.utilities.io.Files.deleteTree(Paths.get("target/angela/kits/3.9.9"));
    }

    initFiles();

    Distribution distribution = distribution(version("3.9.9"), KIT, TERRACOTTA_OS);
    InstanceId instanceId = new InstanceId(UUID.randomUUID().toString(), "client");
    assertEquals(0, executor.getFileTransferQueue(instanceId).size());

    try (Agent agent2 = Agent.hazelcast(agent.getGroupId(), "client-1", portAllocator, executor.getGroup().getPeerAddresses())) {
      Awaitility.await().atMost(10, TimeUnit.SECONDS)
          .until(() -> executor.getGroup().size() == 2);

      executor.uploadKit(agent2.getAgentID(), instanceId, distribution, "ehcache-clustered-3.9.9-kit", Paths.get("target", "files"));
    }

    assertEquals(0, executor.getFileTransferQueue(instanceId).size());
    assertTrue(Files.exists(Paths.get("target/angela/kits/3.9.9/files/two.txt")));
    assertTrue(Files.exists(Paths.get("target/angela/kits/3.9.9/files/sub/three.txt")));
  }

  /**
   * Verifies that a distributed AtomicCounter's initial value is applied exactly once
   * cluster-wide, even when a second node constructs the same counter with the same
   * initial value after the counter has already been modified.
   * This guards against the CAS-from-zero regression fixed in HazelcastClusterPrimitives.
   */
  @Test
  public void testAtomicCounterInitializedOnce() {
    try (Agent agent2 = Agent.hazelcast(agent.getGroupId(), "client-1", portAllocator, executor.getGroup().getPeerAddresses());
         Executor executor2 = agent2.getGridProvider().createExecutor(agent2.getGroupId(), agent2.getAgentID())) {
      // Create counter with a non-zero initial value from the first node and increment it
      AtomicCounter c1 = executor.getCluster().atomicCounter("initOnce", 100L);
      assertEquals(101L, c1.incrementAndGet());

      // Construct the same counter from the second node — initial value must NOT be re-applied
      AtomicCounter c2 = executor2.getCluster().atomicCounter("initOnce", 100L);
      assertEquals(101L, c2.get());

      // Both views must refer to the same underlying value
      assertEquals(102L, c2.incrementAndGet());
      assertEquals(102L, c1.get());
    }
  }

  /**
   * Verifies that HazelcastBarrier correctly synchronises two participants across
   * multiple generations: both threads must get through each await(), must receive
   * distinct indices, and the barrier must reset cleanly for subsequent rounds.
   */
  @Test
  public void testBarrier() throws ExecutionException, InterruptedException, TimeoutException {
    try (Agent agent2 = Agent.hazelcast(agent.getGroupId(), "client-1", portAllocator, executor.getGroup().getPeerAddresses());
         Executor executor2 = agent2.getGridProvider().createExecutor(agent2.getGroupId(), agent2.getAgentID())) {
      Awaitility.await().atMost(10, TimeUnit.SECONDS)
          .until(() -> executor.getGroup().size() == 2 && executor2.getGroup().size() == 2);

      // Each participant creates its own Barrier instance; they coordinate via the CP subsystem
      Barrier b1 = executor.getCluster().barrier("b", 2);
      Barrier b2 = executor2.getCluster().barrier("b", 2);

      java.util.concurrent.ExecutorService threads = java.util.concurrent.Executors.newFixedThreadPool(2);
      try {
        for (int gen = 0; gen < 3; gen++) {
          java.util.concurrent.Future<Integer> f1 = threads.submit((java.util.concurrent.Callable<Integer>) b1::await);
          java.util.concurrent.Future<Integer> f2 = threads.submit((java.util.concurrent.Callable<Integer>) b2::await);

          int i1 = f1.get(15, TimeUnit.SECONDS);
          int i2 = f2.get(15, TimeUnit.SECONDS);

          // Both participants must have received distinct indices covering {0, 1}
          Set<Integer> indices = new HashSet<>(Arrays.asList(i1, i2));
          assertEquals(new HashSet<>(Arrays.asList(0, 1)), indices);
        }
      } finally {
        threads.shutdown();
      }
    }
  }

  private static final AtomicInteger counter = new AtomicInteger();

  private static void initFiles() throws IOException {
    Files.createDirectories(Paths.get("target", "files", "sub"));
    Files.write(Paths.get("target", "one.txt"), new byte[0]);
    Files.write(Paths.get("target", "files", "two.txt"), new byte[0]);
    Files.write(Paths.get("target", "files", "sub", "three.txt"), new byte[0]);
  }
}
