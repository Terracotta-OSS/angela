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
package org.terracotta.angela.agent.com.grid.ignite;

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
import org.terracotta.angela.agent.com.Executor;
import org.terracotta.angela.agent.com.grid.ClusterPrimitives;
import org.terracotta.angela.agent.com.grid.GridProvider;
import org.terracotta.angela.common.AngelaProperties;
import org.terracotta.angela.common.net.PortAllocator;
import org.terracotta.angela.common.util.AngelaVersions;
import org.terracotta.angela.common.util.IpUtils;
import org.zeroturnaround.process.PidUtil;

import java.nio.file.Paths;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.terracotta.angela.common.AngelaProperties.IGNITE_LOGGING;
import static org.terracotta.angela.common.AngelaProperties.getEitherOf;
import static org.terracotta.angela.common.util.FileUtils.createAndValidateDir;

/**
 * Ignite-backed implementation of {@link GridProvider}.
 * <p>
 * Encapsulates all Ignite bootstrap logic: port allocation, configuration,
 * node startup, and the system-shutdown message listener.
 */
public class IgniteGridProvider implements GridProvider {

  private static final Logger logger = LoggerFactory.getLogger(IgniteGridProvider.class);

  private final AgentID agentID;
  private final Ignite ignite;
  private final ClusterPrimitives clusterPrimitives;

  public IgniteGridProvider(UUID group, String instanceName, PortAllocator portAllocator, Collection<String> peers) {
    // Required to avoid a deadlock if a client job causes a system.exit to be run.
    // The agent has its own shutdown hook
    System.setProperty(IgniteSystemProperties.IGNITE_NO_SHUTDOWN_HOOK, "true");

    // do not check for a new version
    System.setProperty(IgniteSystemProperties.IGNITE_UPDATE_NOTIFIER, "false");

    PortAllocator.PortReservation portReservation = portAllocator.reserve(2);
    int igniteDiscoveryPort = portReservation.next();
    int igniteComPort = portReservation.next();
    String hostname = IpUtils.getHostName();

    this.agentID = new AgentID(instanceName, hostname, igniteDiscoveryPort, PidUtil.getMyPid());

    logger.info("Starting Ignite agent: {} with com port: {}...", agentID, igniteComPort);

    // Compute and create the Ignite home directory
    java.nio.file.Path igniteDir = Paths.get(getEitherOf(AngelaProperties.ROOT_DIR, AngelaProperties.KITS_DIR)).resolve("ignite");
    createAndValidateDir(igniteDir);
    logger.info("Agent: {} Ignite directory: {}", agentID, igniteDir);

    IgniteConfiguration cfg = new IgniteConfiguration();
    Map<String, String> userAttributes = new HashMap<>();
    userAttributes.put("angela.version", AngelaVersions.INSTANCE.getAngelaVersion());
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
    cfg.setIgniteHome(igniteDir.resolve(System.getProperty("user.name")).toString());

    cfg.setDiscoverySpi(new TcpDiscoverySpi()
        .setLocalPort(igniteDiscoveryPort)
        .setLocalPortRange(0) // we must not use the range otherwise Ignite might bind to a port not reserved
        .setIpFinder(new TcpDiscoveryVmIpFinder(true).setAddresses(peers)));

    cfg.setCommunicationSpi(new TcpCommunicationSpi()
        .setLocalPort(igniteComPort)
        .setLocalPortRange(0)); // we must not use the range otherwise Ignite might bind to a port not reserved

    logger.info("Connecting agent: {} to peers: {}", agentID, peers);

    try {
      this.ignite = Ignition.start(cfg);
    } catch (IgniteException e) {
      logger.error("Error starting node {}", cfg, e);
      throw new RuntimeException("Error starting node " + agentID, e);
    }
    this.clusterPrimitives = new IgniteClusterPrimitives(ignite);

    final AgentID localAgentID = this.agentID;
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
              logger.info("Agent: {} received a shutdown request. Exiting...", localAgentID);
              System.exit(0);
            }
          }.start();
          return false;
        }
        default:
          return true;
      }
    });

    logger.info("Started agent: {} in group: {}", agentID, group);
  }

  public AgentID getAgentID() {
    return agentID;
  }

  /**
   * Returns the underlying Ignite instance.
   * Intended for use by Ignite-specific infrastructure code (executors, tests).
   */
  public Ignite getIgnite() {
    return ignite;
  }

  @Override
  public Executor createExecutor(UUID group, AgentID agentID) {
    return new IgniteLocalExecutor(group, agentID, ignite, clusterPrimitives);
  }

  @Override
  public ClusterPrimitives createClusterPrimitives() {
    return clusterPrimitives;
  }

  @Override
  public void close() {
    if (ignite != null) {
      try {
        ignite.close();
      } catch (Exception ignored) {
      }
    }
  }
}
