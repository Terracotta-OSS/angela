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
import org.apache.ignite.cluster.ClusterGroup;
import org.apache.ignite.cluster.ClusterTopologyException;
import org.apache.ignite.events.DiscoveryEvent;
import org.apache.ignite.events.Event;
import org.apache.ignite.events.EventType;
import org.apache.ignite.lang.IgniteBiPredicate;
import org.apache.ignite.lang.IgnitePredicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terracotta.angela.agent.Agent;
import org.terracotta.angela.agent.com.AgentGroup;
import org.terracotta.angela.agent.com.AgentID;
import org.terracotta.angela.common.util.AngelaVersions;

import java.io.Serializable;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toMap;

/**
 * @author Mathieu Carbou
 */
public class IgniteAgentGroup extends AgentGroup {
  private static final long serialVersionUID = 1L;

  private static final Logger logger = LoggerFactory.getLogger(IgniteAgentGroup.class);

  private final transient Ignite ignite;

  @SuppressFBWarnings("SE_TRANSIENT_FIELD_NOT_RESTORED")
  private final transient Map<AgentID, CompletableFuture<Void>> shutdowns = new ConcurrentHashMap<>();

  private final Map<AgentID, Meta> discoveredAgents = new ConcurrentHashMap<>();

  IgniteAgentGroup(UUID id, AgentID me, Ignite ignite) {
    super(id, me);
    this.ignite = ignite;

    joined(me, null);

    ignite.events(ignite.cluster().forAttribute("angela.group", getId().toString())).remoteListen(new IgniteBiPredicate<UUID, Event>() {
      private static final long serialVersionUID = 1L;

      @Override
      public boolean apply(UUID uuid, Event event) {
        try {
          switch (event.type()) {
            case EventType.EVT_NODE_JOINED: {
              joined(AgentID.valueOf(((DiscoveryEvent) event).eventNode().attribute("angela.nodeName")), null);
              break;
            }
            case EventType.EVT_NODE_LEFT: {
              left(AgentID.valueOf(((DiscoveryEvent) event).eventNode().attribute("angela.nodeName")));
              break;
            }
            default:
          }
        } catch (Exception e) {
          logger.error("Event: {} error: {}", event, e.getMessage(), e);
        }
        return true;
      }
    }, new IgnitePredicate<Event>() {
      private static final long serialVersionUID = 1L;

      @Override
      public boolean apply(Event event) {
        return true;
      }
    }, EventType.EVT_NODE_LEFT, EventType.EVT_NODE_JOINED);
  }

  @Override
  public Collection<AgentID> getAllAgents() {
    return discoveredAgents.keySet();
  }

  // topology updates

  void joined(AgentID agentID, String hostname) {
    requireNonNull(agentID);
    // hostname can be null

    Meta meta = discoveredAgents.computeIfAbsent(agentID, key -> {
      Map<String, String> attrs = clusterGroup(agentID)
          .map(clusterGroup -> clusterGroup.node().attributes().entrySet().stream()
              .filter(e -> e.getValue() instanceof String)
              .sorted(Map.Entry.comparingByKey())
              .collect(toMap(
                  Map.Entry::getKey,
                  e -> String.valueOf(e.getValue()),
                  (s, s2) -> {
                    throw new UnsupportedOperationException();
                  },
                  LinkedHashMap::new)))
          .orElse(null);

      if (attrs == null) {
        return null;
      }

      if (!attrs.containsKey("angela.group") || !Objects.equals(attrs.get("angela.group"), getId().toString())) {
        throw new IllegalStateException("Agent: " + agentID + " in group: " + attrs.get("angela.group") + " is not part of group: " + getId());
      }
      if (!attrs.containsKey("angela.version") || !Objects.equals(attrs.get("angela.version"), AngelaVersions.INSTANCE.getAngelaVersion())) {
        throw new IllegalStateException("Agent: " + agentID + " is running version [" + attrs.get("angela.version") + "] but the expected version is [" + AngelaVersions.INSTANCE.getAngelaVersion() + "]");
      }

      logger.info("Agent: {} has joined cluster group: {}", agentID, getId());

      return new Meta(attrs, hostname);
    });

    // agent was already existing but we have a hostname update.
    // can happen if ignite discovers the agent faster than the
    // call to joined(agent, hostname) is done after spawning teh agent
    if (meta != null && hostname != null && !meta.hostnames.contains(hostname)) {
      AgentID existing = findRemoteAgentID(hostname).orElse(null);
      if (existing != null && !Objects.equals(agentID, existing)) {
        throw new IllegalStateException("Two agents are serving the same hostname: " + hostname + ": already registered: " + existing + ", new one: " + agentID);
      }
      meta.hostnames.add(hostname);
    }
  }

  private void left(AgentID agentID) {
    Meta meta = discoveredAgents.remove(agentID);
    if (meta != null) {
      meta.hostnames.clear();
      getShutdown(agentID).complete(null);
      logger.info("Agent: {} has left cluster group: {}", agentID, getId());
    }
  }

  // search

  Optional<AgentID> findRemoteAgentID(String hostname) {
    // 1. check if the local agent (orchestrator) has been set to serve the hostname
    // can be the case when using ignite local mode when we skip ssh calls
    {
      final AgentID localAgentID = getLocalAgentID();
      final Meta meta = discoveredAgents.get(localAgentID);
      if (meta != null && meta.hostnames.contains(hostname)) {
        return Optional.of(localAgentID);
      }
    }
    // 2. check if a remote agent has been spawned and set to serve the hostname
    {
      final AgentID agentID = discoveredAgents.entrySet().stream()
          .filter(e -> !e.getKey().isLocal())
          .filter(e -> e.getKey().getName().equals(Agent.AGENT_TYPE_REMOTE))
          .filter(e -> e.getValue().hostnames.contains(hostname))
          .map(Map.Entry::getKey)
          .findFirst()
          .orElse(null);
      if (agentID != null) {
        return Optional.of(agentID);
      }
    }
    return Optional.empty();
  }

  Optional<ClusterGroup> clusterGroup(AgentID agentID) {
    ClusterGroup clusterGroup = ignite.cluster()
        .forAttribute("angela.group", getId().toString())
        .forAttribute("angela.nodeName", agentID.toString());
    if (clusterGroup.nodes().isEmpty()) {
      return Optional.empty();
    }
    if (clusterGroup.nodes().size() > 1) {
      throw new IllegalStateException("Several agents found matching: " + agentID + " in group " + getId());
    }
    return Optional.of(clusterGroup);
  }

  // shutdown

  Optional<CompletableFuture<Void>> requestShutdown(AgentID agentID) {
    final Meta meta = discoveredAgents.get(agentID);
    if (meta == null) {
      return Optional.empty();
    }
    if (!getSpawnedAgents().contains(agentID)) {
      throw new IllegalArgumentException("Cannot kill inline or local agent: " + agentID);
    }
    // get com group
    ClusterGroup clusterGroup = clusterGroup(agentID).orElse(null);
    if (clusterGroup != null) {
      try {
        ignite.message(clusterGroup).send("SYSTEM", "close");
        logger.info("Requested shutdown of agent: {}", agentID);
      } catch (ClusterTopologyException e) {
        // cluster empty or failed to send message because node is gone
        left(agentID);
      }
    } else {
      // com group not available - the node we knew has left suddenly
      left(agentID);
    }
    return Optional.of(getShutdown(agentID));
  }

  private CompletableFuture<Void> getShutdown(AgentID agentID) {
    return shutdowns.computeIfAbsent(agentID, agentID1 -> new CompletableFuture<>());
  }

  private static class Meta implements Serializable {
    private static final long serialVersionUID = 1L;

    final Map<String, String> attrs;
    final Collection<String> hostnames = new ConcurrentLinkedQueue<>();

    Meta(Map<String, String> attrs, String hostname) {
      this.attrs = requireNonNull(attrs);
      if (hostname != null) {
        this.hostnames.add(hostname);
      }
    }
  }
}
