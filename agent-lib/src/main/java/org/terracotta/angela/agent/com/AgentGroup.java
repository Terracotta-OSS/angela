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

import org.terracotta.angela.agent.Agent;
import org.terracotta.angela.common.util.HostPort;

import java.io.Serializable;
import java.util.Collection;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toList;

/**
 * @author Mathieu Carbou
 */
public abstract class AgentGroup implements Serializable {
  private static final long serialVersionUID = 1L;

  private final UUID id;
  private final AgentID agentID;

  public AgentGroup(UUID id, AgentID agentID) {
    this.id = id;
    this.agentID = agentID;
  }

  public final UUID getId() {
    return id;
  }

  public final AgentID getLocalAgentID() {
    return agentID;
  }

  /**
   * A combination of all Ignite agent launched for both remoting (remote agent)
   * or for running client jobs, plus the orchestrator
   * @return collection of agents
   */
  public abstract Collection<AgentID> getAllAgents();

  /**
   * @return all Ignite agent spawned to run client code
   */
  public final Collection<AgentID> getClientAgents() {
    return getAllAgents().stream()
        .filter(agentID -> !agentID.isLocal())
        .filter(agentID -> !agentID.getName().equals(Agent.AGENT_TYPE_ORCHESTRATOR) && !agentID.getName().equals(Agent.AGENT_TYPE_REMOTE))
        .collect(toList());
  }

  /**
   * @return all Ignite agent spawned that must be closed at the end
   */
  public final Collection<AgentID> getSpawnedAgents() {
    return getAllAgents().stream()
        .filter(agentID -> !agentID.isLocal())
        .filter(agentID -> !agentID.getName().equals(Agent.AGENT_TYPE_ORCHESTRATOR))
        .collect(toList());
  }

  /**
   * Only the Ignite nodes started remotely to control process launching, once per hostname.
   * These are not the client agents.
   * @return collection of remote agens IDs
   */
  public final Collection<AgentID> getRemoteAgentIDs() {
    return getAllAgents().stream()
        .filter(agentID -> !agentID.isLocal())
        .filter(agentID -> agentID.getName().equals(Agent.AGENT_TYPE_REMOTE))
        .collect(toList());
  }

  public final Collection<String> getPeerAddresses() {
    return getAllAgents().stream().map(AgentID::getAddress)
            .map(addr -> new HostPort(addr).getHostPort()).collect(toList());
  }

  @Override
  public final String toString() {
    return getId() + "=" + getAllAgents();
  }

  public final int size() {
    return getAllAgents().size();
  }

  public final boolean isEmpty() {
    return getAllAgents().isEmpty();
  }

  public final boolean contains(AgentID agentID) {
    return getAllAgents().contains(agentID);
  }

  public final Stream<AgentID> stream() {
    return getAllAgents().stream();
  }

  public final void forEach(Consumer<? super AgentID> action) {
    getAllAgents().forEach(action);
  }
}
