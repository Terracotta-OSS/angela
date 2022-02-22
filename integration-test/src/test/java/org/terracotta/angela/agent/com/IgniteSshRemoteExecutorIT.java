/*
 * Copyright Terracotta, Inc.
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
import org.terracotta.angela.common.TerracottaCommandLineEnvironment;
import org.terracotta.angela.common.net.DefaultPortAllocator;
import org.terracotta.angela.common.net.PortAllocator;
import org.terracotta.angela.util.SshServer;

import java.nio.file.Paths;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.startsWith;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeThat;

/**
 * @author Mathieu Carbou
 */
public class IgniteSshRemoteExecutorIT {

  PortAllocator portAllocator = new DefaultPortAllocator();

  UUID group = UUID.randomUUID();

  SshServer sshServer = new SshServer(Paths.get("target", "sshd", group.toString()))
      .withPort(portAllocator.reserve(1).next())
      .start();

  Agent agent = Agent.igniteOrchestrator(group, portAllocator);
  AgentID agentID = agent.getAgentID();

  @BeforeClass
  public static void precondition() {
    assumeThat(
        "tests requiring the use of a fake DNS hostname (-Djdk.net.hosts.file=...) cannot run on 1.8",
        System.getProperty("java.version"), not(startsWith("1.8")));
  }

  @After
  public void tearDown() {
    agent.close();
    sshServer.close();
    portAllocator.close();
  }

  @Test
  public void testStartRemoteAgentWithoutToolchain() {
    try (Executor executor = new IgniteSshRemoteExecutor(agent)
        .setStrictHostKeyChecking(false)
        .setPort(sshServer.getPort())) {

      assertEquals(1, executor.getGroup().size());
      assertFalse(executor.findAgentID("testhostname").isPresent());
      Optional<AgentID> agentID = executor.startRemoteAgent("testhostname");
      assertTrue(agentID.isPresent());
      assertEquals(agentID.get(), executor.findAgentID("testhostname").get());
      assertEquals(2, executor.getGroup().size());
      assertTrue(executor.getGroup().contains(agentID.get()));
    }
  }

  @Test
  public void testStartRemoteAgentWithToolchain() {
    try (Executor executor = new IgniteSshRemoteExecutor(agent)
        .setTcEnv(TerracottaCommandLineEnvironment.DEFAULT.withJavaVersion("1.11")) // will spawn a child agent with java 11
        .setStrictHostKeyChecking(false)
        .setPort(sshServer.getPort())) {

      assertEquals(1, executor.getGroup().size());
      assertFalse(executor.findAgentID("testhostname").isPresent());
      Optional<AgentID> agentID = executor.startRemoteAgent("testhostname");
      assertTrue(agentID.isPresent());
      assertEquals(agentID.get(), executor.findAgentID("testhostname").get());
      assertEquals(2, executor.getGroup().size());
      assertTrue(executor.getGroup().contains(agentID.get()));
    }
  }

  @Test
  public void testShutdown() throws TimeoutException {
    try (Executor executor = new IgniteSshRemoteExecutor(agent)
        .setStrictHostKeyChecking(false)
        .setPort(sshServer.getPort())) {

      try {
        executor.shutdown(agentID);
        fail();
      } catch (IllegalArgumentException e) {
        assertTrue(e.getMessage().startsWith("Cannot kill myself"));
      }

      AgentID agentID = executor.startRemoteAgent("testhostname").get();
      executor.shutdown(agentID);

      assertEquals(1, executor.getGroup().size());
      assertFalse(executor.getGroup().contains(agentID));
    }
  }

  @Test
  public void testShutdownAtClose() {
    try (Executor executor = new IgniteSshRemoteExecutor(agent)
        .setStrictHostKeyChecking(false)
        .setPort(sshServer.getPort())) {
      executor.startRemoteAgent("testhostname").get();
    }
    // executor.close() will execute teh shutdown
    assertEquals(1, agent.getIgnite().cluster().forAttribute("angela.group", group.toString()).nodes().size());
  }
}
