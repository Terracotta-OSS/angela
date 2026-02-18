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
package org.terracotta.angela.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terracotta.angela.agent.com.AgentID;
import org.terracotta.angela.agent.com.grid.GridProvider;
import org.terracotta.angela.agent.com.grid.ignite.IgniteGridProvider;
import org.terracotta.angela.common.AngelaProperties;
import org.terracotta.angela.common.net.DefaultPortAllocator;
import org.terracotta.angela.common.net.PortAllocator;
import org.zeroturnaround.process.PidUtil;
import org.zeroturnaround.process.ProcessUtil;
import org.zeroturnaround.process.Processes;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.UUID;

import static org.terracotta.angela.common.AngelaProperties.getEitherOf;
import static org.terracotta.angela.common.util.FileUtils.createAndValidateDir;

/**
 * @author Ludovic Orban
 */
public class Agent implements AutoCloseable {

  public static final String AGENT_TYPE_ORCHESTRATOR = "orchestrator-agent";
  public static final String AGENT_TYPE_REMOTE = "remote-agent";
  public static final String AGENT_IS_READY_MARKER_LOG = "Agent is ready";
  public static final Path ROOT_DIR;
  public static final Path WORK_DIR;

  private static final Logger logger;

  static {
    // the angela-agent jar may end up on the classpath, so its logback config cannot have the default filename
    // this must happen before any Logger instance gets created
    System.setProperty("logback.configurationFile", Boolean.getBoolean("angela.agent.debug") ? "angela-logback-debug.xml" : "angela-logback.xml");
    logger = LoggerFactory.getLogger(Agent.class);

    ROOT_DIR = Paths.get(getEitherOf(AngelaProperties.ROOT_DIR, AngelaProperties.KITS_DIR));
    if (!ROOT_DIR.isAbsolute()) {
      logger.error("Expected ROOT_DIR to be an absolute path, got: {}", ROOT_DIR);
      throw new IllegalArgumentException("Expected ROOT_DIR to be an absolute path, got: " + ROOT_DIR);
    }
    WORK_DIR = ROOT_DIR.resolve("work");
  }

  private final UUID group;
  private final AgentID agentID;
  private final GridProvider gridProvider;

  public Agent(UUID group, AgentID agentID, GridProvider gridProvider) {
    this.group = group;
    this.agentID = agentID;
    this.gridProvider = gridProvider;
  }

  public UUID getGroupId() {
    return group;
  }

  public AgentID getAgentID() {
    return agentID;
  }

  public GridProvider getGridProvider() {
    return gridProvider;
  }

  @Override
  public String toString() {
    return agentID.toString();
  }

  @Override
  public void close() {
    logger.info("Shutting down agent: {}", agentID);
    if (gridProvider != null) {
      gridProvider.close();
    }
  }

  /**
   * main method used when starting a new ignite agent locally or remotely
   */
  public static void main(String[] args) {
    final String instanceName = System.getProperty("angela.instanceName");
    if (instanceName == null) {
      logger.error("angela.instanceName is missing");
      throw new AssertionError("angela.instanceName is missing");
    }

    final String group = System.getProperty("angela.group");
    if (group == null) {
      logger.error("angela.group is missing");
      throw new AssertionError("angela.group is missing");
    }

    DefaultPortAllocator portAllocator = new DefaultPortAllocator();
    Agent agent = ignite(UUID.fromString(group), instanceName, portAllocator, Arrays.asList(System.getProperty("angela.directJoin", "").split(",")));
    AgentID localAgentID = agent.getAgentID();

    logger.info("Agent: {} Root directory: {}", localAgentID, ROOT_DIR);
    logger.info("Agent: {} Work directory: {}", localAgentID, WORK_DIR);

    AgentController agentController = new AgentController(localAgentID, portAllocator);

    // move the agent controller statically so that it can be accessed when ignite remote closures sent to this node are executed
    AgentController.setUniqueInstance(agentController);

    // cleanup ignite agent and reserved ports
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      // because closing ignite (whether from their shutdown hook or ours) can block in a com
      // if the client job is causing the shutdown
      new Thread() {
        {
          setDaemon(true);
        }

        @Override
        public void run() {
          try {
            // let 5 sec for a normal shutdown, otherwise, kill me
            sleep(5_000);
            logger.warn("Forcefully killing agent after 10 seconds");
            ProcessUtil.destroyForcefullyAndWait(Processes.newPidProcess(PidUtil.getMyPid()));
          } catch (IOException | InterruptedException e) {
            e.printStackTrace();
          }
        }
      }.start();

      // try a normal close first
      logger.info("Closing grid provider...");
      agent.close();
      try {
        portAllocator.close();
      } catch (Exception ignored) {
      } finally {
        AgentController.removeUniqueInstance(agentController);
      }
    }));

    // Do not use logger here as the marker is being grep'ed at and we do not want to depend upon the logger config
    System.out.println(AGENT_IS_READY_MARKER_LOG + ": " + localAgentID);
    System.out.flush();
  }

  public static Agent local(UUID group) {
    return new Agent(group, AgentID.local(), null);
  }

  public static Agent igniteOrchestrator(UUID group, PortAllocator portAllocator) {
    return ignite(group, AGENT_TYPE_ORCHESTRATOR, portAllocator, Collections.emptyList());
  }

  public static Agent ignite(UUID group, String instanceName, PortAllocator portAllocator, Collection<String> peers) {
    createAndValidateDir(Agent.ROOT_DIR);
    createAndValidateDir(Agent.WORK_DIR);
    IgniteGridProvider provider = new IgniteGridProvider(group, instanceName, portAllocator, peers);
    return new Agent(group, provider.getAgentID(), provider);
  }
}
