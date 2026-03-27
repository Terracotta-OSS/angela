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

import com.hazelcast.cluster.Member;
import com.hazelcast.cluster.MembershipEvent;
import com.hazelcast.cluster.MembershipListener;
import com.hazelcast.core.HazelcastInstance;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
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
 * Hazelcast-backed implementation of {@link AgentGroup}.
 * Tracks cluster membership by listening to Hazelcast {@link MembershipListener} events,
 * filtering to agents that belong to the same angela group.
 */
class HazelcastAgentGroup extends AgentGroup {
  private static final long serialVersionUID = 1L;

  private static final Logger logger = LoggerFactory.getLogger(HazelcastAgentGroup.class);

  @SuppressFBWarnings("SE_TRANSIENT_FIELD_NOT_RESTORED")
  private final transient HazelcastInstance hz;

  @SuppressFBWarnings("SE_TRANSIENT_FIELD_NOT_RESTORED")
  private final transient Map<AgentID, CompletableFuture<Void>> shutdowns = new ConcurrentHashMap<>();

  private final Map<AgentID, Meta> discoveredAgents = new ConcurrentHashMap<>();

  HazelcastAgentGroup(UUID id, AgentID me, HazelcastInstance hz) {
    super(id, me);
    this.hz = hz;

    // Register local agent first
    joined(me, null);

    // Listen for future membership changes.
    // Register BEFORE scanning existing members to close the window where a member could
    // join between the scan and the listener registration and be missed.
    hz.getCluster().addMembershipListener(new MembershipListener() {
      @Override
      public void memberAdded(MembershipEvent event) {
        try {
          String nodeName = event.getMember().getAttribute("angela.nodeName");
          if (nodeName != null) {
            joined(AgentID.valueOf(nodeName), null);
          }
        } catch (Exception e) {
          logger.error("MemberAdded event error: {}", e.getMessage(), e);
        }
      }

      @Override
      public void memberRemoved(MembershipEvent event) {
        try {
          String nodeName = event.getMember().getAttribute("angela.nodeName");
          if (nodeName != null) {
            left(AgentID.valueOf(nodeName));
          }
        } catch (Exception e) {
          logger.error("MemberRemoved event error: {}", e.getMessage(), e);
        }
      }
    });

    // Bootstrap from members that were already in the cluster before the listener registered.
    // joined() uses computeIfAbsent so duplicate calls from the listener are harmless.
    for (Member member : hz.getCluster().getMembers()) {
      if (member.localMember()) {
        continue;
      }
      String nodeName = member.getAttribute("angela.nodeName");
      if (nodeName != null) {
        try {
          joined(AgentID.valueOf(nodeName), null);
        } catch (Exception e) {
          logger.error("Bootstrap member error for {}: {}", nodeName, e.getMessage(), e);
        }
      }
    }
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
      Map<String, String> attrs = findMember(agentID)
          .map(member -> member.getAttributes().entrySet().stream()
              .sorted(Map.Entry.comparingByKey())
              .collect(toMap(
                  Map.Entry::getKey,
                  Map.Entry::getValue,
                  (s, s2) -> {
                    throw new UnsupportedOperationException();
                  },
                  LinkedHashMap::new)))
          .orElse(null);

      if (attrs == null) {
        return null;
      }

      if (!attrs.containsKey("angela.group") || !Objects.equals(attrs.get("angela.group"), getId().toString())) {
        // Member belongs to a different group — ignore silently
        return null;
      }
      if (!attrs.containsKey("angela.version") || !Objects.equals(attrs.get("angela.version"), AngelaVersions.INSTANCE.getAngelaVersion())) {
        throw new IllegalStateException("Agent: " + agentID + " is running version [" + attrs.get("angela.version") + "] but the expected version is [" + AngelaVersions.INSTANCE.getAngelaVersion() + "]");
      }

      logger.info("Agent: {} has joined cluster group: {}", agentID, getId());

      return new Meta(attrs, hostname);
    });

    // agent was already existing but we have a hostname update
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

  Optional<Member> findMember(AgentID agentID) {
    return hz.getCluster().getMembers().stream()
        .filter(m -> agentID.toString().equals(m.getAttribute("angela.nodeName")))
        .filter(m -> getId().toString().equals(m.getAttribute("angela.group")))
        .findFirst();
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
    Member member = findMember(agentID).orElse(null);
    if (member != null) {
      try {
        // Send targeted shutdown message on per-agent topic
        hz.getTopic("angela-system-" + agentID).publish("close");
        logger.info("Requested shutdown of agent: {}", agentID);
      } catch (Exception e) {
        // Member may have already left
        left(agentID);
      }
    } else {
      // Member not found — the node we knew has left suddenly
      left(agentID);
    }
    return Optional.of(getShutdown(agentID));
  }

  private CompletableFuture<Void> getShutdown(AgentID agentID) {
    return shutdowns.computeIfAbsent(agentID, id -> new CompletableFuture<>());
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
