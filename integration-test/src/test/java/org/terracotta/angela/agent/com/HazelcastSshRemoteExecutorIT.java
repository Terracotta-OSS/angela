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

import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;
import org.terracotta.angela.agent.Agent;
import org.terracotta.angela.agent.com.grid.hazelcast.HazelcastGridProvider;
import org.terracotta.angela.agent.com.grid.hazelcast.HazelcastSshRemoteExecutor;
import org.terracotta.angela.agent.com.RemoteCallable;
import org.terracotta.angela.common.TerracottaCommandLineEnvironment;
import org.terracotta.angela.common.net.DefaultPortAllocator;
import org.terracotta.angela.common.net.PortAllocator;
import org.terracotta.angela.common.util.OS;
import org.terracotta.angela.util.SshServer;

import java.nio.file.Paths;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.startsWith;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeThat;

/**
 * Integration tests for {@link HazelcastSshRemoteExecutor}, mirroring {@link IgniteSshRemoteExecutorIT}.
 */
public class HazelcastSshRemoteExecutorIT {

  PortAllocator portAllocator = new DefaultPortAllocator();

  UUID group = UUID.randomUUID();

  SshServer sshServer = new SshServer(Paths.get("target", "sshd", group.toString()))
      .withPort(portAllocator.reserve(1).next())
      .start();

  Agent agent = Agent.hazelcastOrchestrator(group, portAllocator);
  AgentID agentID = agent.getAgentID();

  @BeforeClass
  public static void precondition() {
    assumeThat(
        "tests requiring the use of a fake DNS hostname (-Djdk.net.hosts.file=...) cannot run on 1.8",
        System.getProperty("java.version"), not(startsWith("1.8")));

    assumeThat("SSH tests can only be run on Linux", OS.INSTANCE.isWindows(), is(false));
  }

  @After
  public void tearDown() {
    agent.close();
    sshServer.close();
    portAllocator.close();
  }

  @Test
  public void testStartRemoteAgentWithoutToolchain() {
    try (Executor executor = new HazelcastSshRemoteExecutor(agent).setPort(sshServer.getPort())) {

      assertEquals(1, executor.getGroup().size());
      assertFalse(executor.findAgentID("testhostname").isPresent());
      Optional<AgentID> remoteAgentID = executor.startRemoteAgent("testhostname");
      assertTrue(remoteAgentID.isPresent());
      assertEquals(remoteAgentID.get(), executor.findAgentID("testhostname").get());
      assertEquals(2, executor.getGroup().size());
      assertTrue(executor.getGroup().contains(remoteAgentID.get()));
    }
  }

  @Test
  public void testStartRemoteAgentWithToolchain() {
    try (Executor executor = new HazelcastSshRemoteExecutor(agent)
        .setTcEnv(TerracottaCommandLineEnvironment.DEFAULT.withJavaVersion("1.11"))
        .setPort(sshServer.getPort())) {

      assertEquals(1, executor.getGroup().size());
      assertFalse(executor.findAgentID("testhostname").isPresent());
      Optional<AgentID> remoteAgentID = executor.startRemoteAgent("testhostname");
      assertTrue(remoteAgentID.isPresent());
      assertEquals(remoteAgentID.get(), executor.findAgentID("testhostname").get());
      assertEquals(2, executor.getGroup().size());
      assertTrue(executor.getGroup().contains(remoteAgentID.get()));
    }
  }

  @Test
  public void testShutdown() throws ExecutionException, InterruptedException {
    try (Executor executor = new HazelcastSshRemoteExecutor(agent).setPort(sshServer.getPort())) {

      try {
        executor.shutdown(agentID).get().get();
        fail();
      } catch (IllegalArgumentException e) {
        assertTrue(e.getMessage().startsWith("Cannot kill myself"));
      }

      AgentID remoteAgentID = executor.startRemoteAgent("testhostname").get();
      executor.shutdown(remoteAgentID).get().get();

      assertEquals(1, executor.getGroup().size());
      assertFalse(executor.getGroup().contains(remoteAgentID));
    }
  }

  /**
   * Verifies the full dispatch round-trip: spawn a Hazelcast agent over SSH, execute a
   * serializable callable on it, and receive the result back in the orchestrator JVM.
   * Hazelcast user code deployment carries the lambda class to the remote process.
   */
  @Test
  public void testExecuteOnRemoteAgent() throws ExecutionException, InterruptedException {
    try (Executor executor = new HazelcastSshRemoteExecutor(agent).setPort(sshServer.getPort())) {
      AgentID remoteAgentID = executor.startRemoteAgent("testhostname").get();

      Integer result = executor.<Integer>executeAsync(remoteAgentID, (RemoteCallable<Integer>) () -> 42).get();

      assertEquals(Integer.valueOf(42), result);
    }
  }

  @Test
  public void testShutdownAtClose() {
    try (Executor executor = new HazelcastSshRemoteExecutor(agent).setPort(sshServer.getPort())) {
      executor.startRemoteAgent("testhostname").get();
    }
    // after executor.close(), all spawned remote agents should have been shut down
    long remaining = ((HazelcastGridProvider) agent.getGridProvider()).getHazelcastInstance()
        .getCluster().getMembers().stream()
        .filter(m -> group.toString().equals(m.getAttribute("angela.group")))
        .count();
    assertEquals(1L, remaining);
  }
}
