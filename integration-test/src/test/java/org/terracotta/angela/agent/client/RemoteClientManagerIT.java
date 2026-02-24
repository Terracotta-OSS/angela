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
package org.terracotta.angela.agent.client;

import org.junit.After;
import org.junit.Test;
import org.terracotta.angela.agent.Agent;
import org.terracotta.angela.agent.com.AgentID;
import org.terracotta.angela.agent.com.Executor;
import org.terracotta.angela.agent.com.grid.ignite.IgniteLocalExecutor;
import org.terracotta.angela.common.TerracottaCommandLineEnvironment;
import org.terracotta.angela.common.net.DefaultPortAllocator;
import org.terracotta.angela.common.net.PortAllocator;
import org.terracotta.angela.common.topology.InstanceId;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * @author Mathieu Carbou
 */
public class RemoteClientManagerIT {

  UUID group = UUID.randomUUID();
  PortAllocator portAllocator = new DefaultPortAllocator();
  Agent agent = Agent.igniteOrchestrator(group, portAllocator);
  Executor executor = agent.getGridProvider().createExecutor(agent.getGroupId(), agent.getAgentID());

  @After
  public void tearDown() {
    executor.close();
    agent.close();
    portAllocator.close();
  }

  @Test
  public void testSpawnClient() throws IOException {
    final Path jar = Files.list(Paths.get("../agent/target"))
        .filter(Files::isRegularFile)
        .filter(path -> path.getFileName().toString().endsWith("-SNAPSHOT.jar"))
        .filter(path -> path.getFileName().toString().startsWith("angela-agent-"))
        .findFirst().get();

    InstanceId instanceId = new InstanceId(UUID.randomUUID().toString(), "client");
    RemoteClientManager remoteClientManager = new RemoteClientManager(instanceId);
    Path lib = remoteClientManager.getClientClasspathRoot();

    Files.createDirectories(lib);
    org.terracotta.utilities.io.Files.copy(jar, lib.resolve(jar.getFileName()));

    AgentID clientAgentID = remoteClientManager.spawnClient(TerracottaCommandLineEnvironment.DEFAULT, executor.getGroup());

    assertEquals(instanceId.toString(), clientAgentID.getName());

    assertEquals(2, executor.getGroup().size());
    assertTrue(executor.getGroup().contains(clientAgentID));
  }
}