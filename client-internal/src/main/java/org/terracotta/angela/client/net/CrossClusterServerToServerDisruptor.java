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
package org.terracotta.angela.client.net;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terracotta.angela.agent.AgentController;
import org.terracotta.angela.agent.com.Executor;
import org.terracotta.angela.common.net.Disruptor;
import org.terracotta.angela.common.net.DisruptorState;
import org.terracotta.angela.common.tcconfig.ServerSymbolicName;
import org.terracotta.angela.common.tcconfig.TerracottaServer;
import org.terracotta.angela.common.topology.InstanceId;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.terracotta.angela.common.DisruptionConstants.CROSS_CLUSTER_KEY_PREFIX;

/**
 * Disrupts the traffic between one or more source servers and their cross-cluster peers.
 */
public class CrossClusterServerToServerDisruptor implements Disruptor {
  private static final Logger logger = LoggerFactory.getLogger(CrossClusterServerToServerDisruptor.class);

  private final Executor executor;
  private final InstanceId instanceId;
  private final List<TerracottaServer> sourceServers;
  private volatile DisruptorState state;

  /**
   * Creates a cross-cluster disruptor for the given source servers.
   *
   * @param executor      Executor for remote operations
   * @param instanceId    InstanceId of the Tsa owning the source servers
   * @param sourceServers The source servers whose cross-cluster peer connections will be disrupted
   */
  public CrossClusterServerToServerDisruptor(Executor executor, InstanceId instanceId, TerracottaServer... sourceServers) {
    this.executor = executor;
    this.instanceId = instanceId;
    this.sourceServers = Arrays.asList(sourceServers);
    this.state = DisruptorState.UNDISRUPTED;
  }

  @Override
  public void disrupt() {
    if (state != DisruptorState.UNDISRUPTED) {
      throw new IllegalStateException("Illegal state before disrupt: " + state);
    }
    for (TerracottaServer sourceServer : sourceServers) {
      logger.info("Disrupting cross-cluster link: {} ({}:{}) -> peer ({}:{})", sourceServer.getServerSymbolicName().getSymbolicName(), sourceServer.getHostName(), sourceServer.getTsaGroupPort(),
          sourceServer.getRelayHostName(), sourceServer.getRelayGroupPort());
      executor.execute(executor.getAgentID(sourceServer.getHostName()), () -> AgentController.getInstance().disrupt(instanceId, sourceServer, Collections.singletonList(buildProxyTarget(sourceServer))));
    }
    state = DisruptorState.DISRUPTED;
  }

  @Override
  public void undisrupt() {
    if (state != DisruptorState.DISRUPTED) {
      throw new IllegalStateException("Illegal state before undisrupt: " + state);
    }
    for (TerracottaServer sourceServer : sourceServers) {
      logger.info("Undisrupting cross-cluster link: {} ({}:{}) -> peer ({}:{})", sourceServer.getServerSymbolicName().getSymbolicName(), sourceServer.getHostName(), sourceServer.getTsaGroupPort(),
          sourceServer.getRelayHostName(), sourceServer.getRelayGroupPort());
      executor.execute(executor.getAgentID(sourceServer.getHostName()), () -> AgentController.getInstance().undisrupt(instanceId, sourceServer, Collections.singletonList(buildProxyTarget(sourceServer))));
    }
    state = DisruptorState.UNDISRUPTED;
  }

  private static TerracottaServer buildProxyTarget(TerracottaServer sourceServer) {
    ServerSymbolicName crossClusterKey = new ServerSymbolicName(CROSS_CLUSTER_KEY_PREFIX + sourceServer.getServerSymbolicName().getSymbolicName());
    return TerracottaServer.server(crossClusterKey.getSymbolicName(), sourceServer.getHostName());
  }

  @Override
  public void close() {
    if (state == DisruptorState.DISRUPTED) {
      undisrupt();
    }
    state = DisruptorState.CLOSED;
  }

  @Override
  public String toString() {
    return "CrossClusterServerToServerDisruptor{" +
        "sources=" + sourceServers.stream()
        .map(s -> s.getServerSymbolicName().getSymbolicName())
        .collect(Collectors.joining(",", "[", "]")) +
        ", state=" + state +
        '}';
  }
}
