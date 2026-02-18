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

import org.terracotta.angela.agent.com.RemoteCallable;
import org.terracotta.angela.agent.com.RemoteRunnable;
import org.terracotta.angela.agent.com.grid.ignite.IgniteLocalExecutor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.terracotta.angela.agent.Agent;
import org.terracotta.angela.common.cluster.AtomicCounter;
import org.terracotta.angela.common.cluster.Cluster;
import org.terracotta.angela.common.distribution.Distribution;
import org.terracotta.angela.common.net.DefaultPortAllocator;
import org.terracotta.angela.common.net.PortAllocator;
import org.terracotta.angela.common.topology.InstanceId;
import org.terracotta.angela.common.util.IpUtils;
import org.zeroturnaround.process.PidUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
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
 * @author Mathieu Carbou
 */
public class IgniteLocalExecutorIT {

  UUID group = UUID.randomUUID();
  transient PortAllocator portAllocator = new DefaultPortAllocator();
  transient Agent agent = Agent.igniteOrchestrator(group, portAllocator);
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

    // a remote host
    assertFalse(executor.findAgentID("foo").isPresent());

    assertEquals(agentID, executor.findAgentID(IpUtils.getHostName()).get());
    assertEquals(agentID, executor.findAgentID("localhost").get());
  }

  @Test
  public void testStartRemoteAgent() {
    // when using only ignite locally, remote hosts calls happen locally
    // startRemoteAgent just registers this remote host
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
    try (Agent client = Agent.ignite(agent.getGroupId(), "client-1", portAllocator, group.getPeerAddresses())) {
      group = executor.getGroup();
      System.out.println(group);
      assertEquals(this.group, group.getId());
      assertEquals(2, group.size());
      assertTrue(group.contains(client.getAgentID()));
    }
  }

  @Test
  public void testGetCluster() {
    final Cluster cluster = executor.getCluster();
    final AtomicCounter counter = cluster.atomicCounter("c", 0);
    try (Agent agent2 = Agent.ignite(agent.getGroupId(), "client-1", portAllocator, executor.getGroup().getPeerAddresses());
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
    try (Agent agent2 = Agent.ignite(agent.getGroupId(), "client-1", portAllocator, executor.getGroup().getPeerAddresses());
         Executor executor2 = agent2.getGridProvider().createExecutor(agent2.getGroupId(), agent2.getAgentID())) {
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

    try (Agent agent2 = Agent.ignite(agent.getGroupId(), "client-1", portAllocator, executor.getGroup().getPeerAddresses())) {
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

    try (Agent agent2 = Agent.ignite(agent.getGroupId(), "client-1", portAllocator, executor.getGroup().getPeerAddresses())) {
      executor.uploadKit(agent2.getAgentID(), instanceId, distribution, "ehcache-clustered-3.9.9-kit", Paths.get("target", "files"));
    }

    assertEquals(0, executor.getFileTransferQueue(instanceId).size());
    assertTrue(Files.exists(Paths.get("target/angela/kits/3.9.9/files/two.txt")));
    assertTrue(Files.exists(Paths.get("target/angela/kits/3.9.9/files/sub/three.txt")));
  }

  private static final AtomicInteger counter = new AtomicInteger();

  private static void initFiles() throws IOException {
    Files.createDirectories(Paths.get("target", "files", "sub"));
    Files.write(Paths.get("target", "one.txt"), new byte[0]);
    Files.write(Paths.get("target", "files", "two.txt"), new byte[0]);
    Files.write(Paths.get("target", "files", "sub", "three.txt"), new byte[0]);
  }
}