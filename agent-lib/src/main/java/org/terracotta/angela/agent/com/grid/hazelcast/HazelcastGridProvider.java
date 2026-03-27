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
package org.terracotta.angela.agent.com.grid.hazelcast;

import com.hazelcast.config.Config;
import com.hazelcast.config.UserCodeDeploymentConfig;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terracotta.angela.agent.com.AgentID;
import org.terracotta.angela.agent.com.Executor;
import org.terracotta.angela.agent.com.grid.ClusterPrimitives;
import org.terracotta.angela.agent.com.grid.GridProvider;
import org.terracotta.angela.common.net.PortAllocator;
import org.terracotta.angela.common.util.AngelaVersions;
import org.terracotta.angela.common.util.IpUtils;
import org.zeroturnaround.process.PidUtil;

import java.util.Collection;
import java.util.UUID;

/**
 * Hazelcast-backed implementation of {@link GridProvider}.
 * <p>
 * Encapsulates all Hazelcast bootstrap logic: port allocation, TCP-IP discovery,
 * CP subsystem, user code deployment, and the system-shutdown message listener.
 */
public class HazelcastGridProvider implements GridProvider {

  private static final Logger logger = LoggerFactory.getLogger(HazelcastGridProvider.class);

  private final AgentID agentID;
  private final HazelcastInstance hz;
  private final ClusterPrimitives clusterPrimitives;

  public HazelcastGridProvider(UUID group, String instanceName, PortAllocator portAllocator, Collection<String> peers) {
    PortAllocator.PortReservation portReservation = portAllocator.reserve(1);
    int hazelcastPort = portReservation.next();
    String hostname = IpUtils.getHostName();

    this.agentID = new AgentID(instanceName, hostname, hazelcastPort, PidUtil.getMyPid());

    logger.info("Starting Hazelcast agent: {}...", agentID);

    Config config = new Config();
    config.setInstanceName(agentID.getNodeName());

    // Member attributes — used for group filtering and version checks
    config.getMemberAttributeConfig().setAttribute("angela.version", AngelaVersions.INSTANCE.getAngelaVersion());
    config.getMemberAttributeConfig().setAttribute("angela.nodeName", agentID.toString());
    config.getMemberAttributeConfig().setAttribute("angela.group", group.toString());
    config.getMemberAttributeConfig().setAttribute("angela.process", System.getProperty("angela.process", "inline"));

    // Network: bind to the allocated port, no port auto-increment
    config.getNetworkConfig().setPort(hazelcastPort).setPortAutoIncrement(false);

    // Discovery: TCP-IP, multicast disabled
    config.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
    config.getNetworkConfig().getJoin().getTcpIpConfig().setEnabled(true);
    for (String peer : peers) {
      config.getNetworkConfig().getJoin().getTcpIpConfig().addMember(peer);
    }

    // CP subsystem: 0 = "unsafe" (AP) mode. Hazelcast 5.x only accepts 0 or >=3;
    // 0 keeps the full CP API (IAtomicLong, FencedLock, ICountDownLatch, IAtomicReference)
    // available with AP rather than Raft semantics. Angela only needs in-cluster atomic
    // coordination, not Raft fault tolerance, so AP semantics are sufficient.
    config.getCPSubsystemConfig().setCPMemberCount(0);

    // User code deployment: allows remote members to load classes from this member
    config.getUserCodeDeploymentConfig()
        .setEnabled(true)
        .setClassCacheMode(UserCodeDeploymentConfig.ClassCacheMode.ETERNAL);

    logger.info("Connecting Hazelcast agent: {} to peers: {}", agentID, peers);

    this.hz = Hazelcast.newHazelcastInstance(config);
    this.clusterPrimitives = new HazelcastClusterPrimitives(hz);

    // Register shutdown listener on per-agent topic
    final AgentID localAgentID = this.agentID;
    hz.getTopic("angela-system-" + localAgentID).addMessageListener(message -> {
      if ("close".equals(message.getMessageObject())) {
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
      }
    });

    logger.info("Started Hazelcast agent: {} in group: {}", agentID, group);
  }

  public AgentID getAgentID() {
    return agentID;
  }

  /**
   * Returns the underlying Hazelcast instance.
   * Intended for use by Hazelcast-specific infrastructure code (executors, tests).
   */
  public HazelcastInstance getHazelcastInstance() {
    return hz;
  }

  @Override
  public Executor createExecutor(UUID group, AgentID agentID) {
    return new HazelcastExecutor(group, agentID, hz, clusterPrimitives);
  }

  @Override
  public ClusterPrimitives createClusterPrimitives() {
    return clusterPrimitives;
  }

  @Override
  public void close() {
    if (hz != null) {
      try {
        hz.shutdown();
      } catch (Exception ignored) {
      }
    }
  }
}
