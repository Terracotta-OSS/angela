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

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteException;
import org.apache.ignite.IgniteSystemProperties;
import org.apache.ignite.Ignition;
import org.apache.ignite.ShutdownPolicy;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.logger.NullLogger;
import org.apache.ignite.logger.slf4j.Slf4jLogger;
import org.apache.ignite.spi.communication.tcp.TcpCommunicationSpi;
import org.apache.ignite.spi.discovery.tcp.TcpDiscoverySpi;
import org.apache.ignite.spi.discovery.tcp.ipfinder.vm.TcpDiscoveryVmIpFinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terracotta.angela.agent.com.AgentID;
import org.terracotta.angela.common.AngelaProperties;
import org.terracotta.angela.common.net.DefaultPortAllocator;
import org.terracotta.angela.common.net.PortAllocator;
import org.terracotta.angela.common.util.AngelaVersion;
import org.terracotta.angela.common.util.IpUtils;
import org.zeroturnaround.process.PidUtil;
import org.zeroturnaround.process.ProcessUtil;
import org.zeroturnaround.process.Processes;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.terracotta.angela.common.AngelaProperties.IGNITE_LOGGING;
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
  private static final Path IGNITE_DIR;

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
    IGNITE_DIR = ROOT_DIR.resolve("ignite");
  }

  private final UUID group;
  private final AgentID agentID;
  private final Ignite ignite;

  public Agent(UUID group, AgentID agentID, Ignite ignite) {
    this.group = group;
    this.agentID = agentID;
    this.ignite = ignite;
  }

  public UUID getGroupId() {
    return group;
  }

  public AgentID getAgentID() {
    return agentID;
  }

  public Ignite getIgnite() {
    return ignite;
  }

  @Override
  public String toString() {
    return agentID.toString();
  }

  @Override
  public void close() {
    logger.info("Shutting down agent: {}", agentID);
    if (ignite != null) {
      try {
        ignite.close();
      } catch (Exception ignored) {
      }
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
    logger.info("Agent: {} Ignite directory: {}", localAgentID, IGNITE_DIR);

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
      logger.info("Closing Ignite...");
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

  @SuppressWarnings("SwitchStatementWithTooFewBranches")
  public static Agent ignite(UUID group, String instanceName, PortAllocator portAllocator, Collection<String> peers) {
    // Required to avoid a deadlock if a client job causes a system.exit to be run.
    // The agent has its own shutdown hook
    System.setProperty(IgniteSystemProperties.IGNITE_NO_SHUTDOWN_HOOK, "true");

    // do not check for a new version
    System.setProperty(IgniteSystemProperties.IGNITE_UPDATE_NOTIFIER, "false");

    createAndValidateDir(Agent.ROOT_DIR);
    createAndValidateDir(Agent.WORK_DIR);
    createAndValidateDir(Agent.IGNITE_DIR);

    PortAllocator.PortReservation portReservation = portAllocator.reserve(2);
    int igniteDiscoveryPort = portReservation.next();
    int igniteComPort = portReservation.next();
    String hostname = IpUtils.getHostName();

    AgentID agentID = new AgentID(instanceName, hostname, igniteDiscoveryPort, PidUtil.getMyPid());

    logger.info("Starting Ignite agent: {} with com port: {}...", agentID, igniteComPort);

    IgniteConfiguration cfg = new IgniteConfiguration();
    Map<String, String> userAttributes = new HashMap<>();
    userAttributes.put("angela.version", AngelaVersion.getAngelaVersion());
    userAttributes.put("angela.nodeName", agentID.toString());
    userAttributes.put("angela.group", group.toString());
    // set how the agent was started: inline == embedded in jvm, spawned == agent has its own JVM
    userAttributes.put("angela.process", System.getProperty("angela.process", "inline"));
    cfg.setUserAttributes(userAttributes);

    boolean enableLogging = Boolean.getBoolean(IGNITE_LOGGING.getValue());
    cfg.setShutdownPolicy(ShutdownPolicy.IMMEDIATE);
    cfg.setGridLogger(enableLogging ? new Slf4jLogger() : new NullLogger());
    cfg.setPeerClassLoadingEnabled(true);
    cfg.setMetricsLogFrequency(0);
    cfg.setIgniteInstanceName(agentID.getNodeName());
    cfg.setIgniteHome(IGNITE_DIR.resolve(System.getProperty("user.name")).toString());

    cfg.setDiscoverySpi(new TcpDiscoverySpi()
        .setLocalPort(igniteDiscoveryPort)
        .setLocalPortRange(0) // we must not use the range otherwise Ignite might bind to a port not reserved
        .setIpFinder(new TcpDiscoveryVmIpFinder(true).setAddresses(peers)));

    cfg.setCommunicationSpi(new TcpCommunicationSpi()
        .setLocalPort(igniteComPort)
        .setLocalPortRange(0)); // we must not use the range otherwise Ignite might bind to a port not reserved

    logger.info("Connecting agent: {} to peers: {}", agentID, peers);

    Ignite ignite;
    try {
      ignite = Ignition.start(cfg);
    } catch (IgniteException e) {
      logger.error("Error starting node {}", cfg, e);
      throw new RuntimeException("Error starting node " + agentID, e);
    }

    ignite.message(ignite.cluster().forRemotes()).localListen("SYSTEM", (uuid, msg) -> {
      switch (String.valueOf(msg)) {
        case "close": {
          new Thread() {
            {
              setDaemon(true);
            }

            @SuppressFBWarnings("DM_EXIT")
            @Override
            public void run() {
              logger.info("Agent: {} received a shutdown request. Exiting...", agentID);
              System.exit(0);
            }
          }.start();
          return false;
        }
        default:
          return true;
      }
    });

    Agent agent = new Agent(group, agentID, ignite);
    logger.info("Started agent: {} in group: {}", agentID, agent.getGroupId());

    return agent;
  }
}
