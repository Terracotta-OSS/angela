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

import org.apache.ignite.lang.IgniteCallable;
import org.apache.ignite.lang.IgniteRunnable;
import org.junit.Before;
import org.junit.Test;
import org.terracotta.angela.agent.Agent;
import org.terracotta.angela.common.clientconfig.ClientId;
import org.terracotta.angela.common.clientconfig.ClientSymbolicName;
import org.terracotta.angela.common.distribution.Distribution;
import org.terracotta.angela.common.topology.InstanceId;
import org.terracotta.angela.common.util.IpUtils;
import org.terracotta.angela.common.util.Pids;

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
public class IgniteFreeExecutorIT {

  UUID group = UUID.randomUUID();
  Agent agent = Agent.local(group);
  Executor executor = new IgniteFreeExecutor(agent);

  @Before
  public void setUp() {
    counter.set(0);
  }

  @Test
  public void testGetLocalAgentID() {
    assertEquals("local#" + Pids.current() + "@" + IpUtils.getHostName() + "#0", executor.getLocalAgentID().toString());
  }

  @Test
  public void testFindAgentID() {
    assertTrue(executor.findAgentID(IpUtils.getHostName()).isPresent());
    assertTrue(executor.findAgentID("localhost").isPresent());
    assertTrue(executor.findAgentID("foo").isPresent());

    assertEquals(AgentID.local(), executor.findAgentID(IpUtils.getHostName()).get());
    assertEquals(AgentID.local(), executor.findAgentID("localhost").get());
    assertEquals(AgentID.local(), executor.findAgentID("foo").get());
  }

  @Test
  public void testStartRemoteAgent() {
    // do not spawn anything without ignite
    assertFalse(executor.startRemoteAgent("foo").isPresent());
    // any hostname would use the local agent id
    assertTrue(executor.findAgentID("foo").isPresent());
    assertEquals(AgentID.local(), executor.findAgentID("foo").get());
  }

  @Test
  public void testGetGroup() {
    AgentGroup group = executor.getGroup();
    assertEquals(this.group, group.getId());
    assertEquals(1, group.size());
    assertEquals(AgentID.local(), group.getAllAgents().iterator().next());
  }

  @Test(expected = UnsupportedOperationException.class)
  public void testGetCluster() {
    executor.getCluster();
  }

  @Test(expected = UnsupportedOperationException.class)
  public void testGetClusterClientId() {
    executor.getCluster(new ClientId(new ClientSymbolicName("foo"), "localhost"));
  }

  @Test
  public void testExecute() throws ExecutionException, InterruptedException {
    executor.execute(AgentID.local(), (IgniteRunnable) () -> counter.incrementAndGet());
    assertEquals(1, counter.get());

    executor.executeAsync(AgentID.local(), (IgniteRunnable) () -> counter.incrementAndGet()).get();
    assertEquals(2, counter.get());

    assertEquals(3, executor.execute(AgentID.local(), (IgniteCallable<? extends Object>) () -> counter.incrementAndGet()));
    assertEquals(4, executor.executeAsync(AgentID.local(), (IgniteCallable<? extends Object>) () -> counter.incrementAndGet()).get());
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
    executor.uploadClientJars(AgentID.local(), instanceId, asList(Paths.get("target", "one.txt"), Paths.get("target", "files")));
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
    executor.uploadKit(AgentID.local(), instanceId, distribution, "ehcache-clustered-3.9.9-kit", Paths.get("target", "files"));
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
