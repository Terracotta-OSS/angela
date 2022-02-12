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
package org.terracotta.angela.agent;

import org.apache.ignite.Ignite;
import org.apache.ignite.cluster.ClusterGroup;
import org.junit.Test;
import org.terracotta.angela.agent.com.AgentID;
import org.terracotta.angela.agent.com.Executor;
import org.terracotta.angela.agent.com.IgniteFreeExecutor;
import org.terracotta.angela.common.net.DefaultPortAllocator;
import org.terracotta.angela.common.util.IpUtils;
import org.zeroturnaround.process.PidUtil;

import java.util.Collection;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.stream.Collectors.toList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * @author Mathieu Carbou
 */
public class AgentIT {

  @Test
  public void testIgniteFree() {
    UUID group = UUID.randomUUID();
    try (Agent agent = Agent.local(group);
         Executor executor = new IgniteFreeExecutor(agent)) {
      final AgentID agentID = agent.getAgentID();
      assertTrue(agentID.isIgniteFree());
      assertEquals("local#" + PidUtil.getMyPid() + "@" + IpUtils.getHostName() + "#0", agentID.toString());
      assertEquals(1, executor.getGroup().size());
    }
  }

  @Test
  public void testIgnite() {
    UUID group = UUID.randomUUID();
    try (DefaultPortAllocator portAllocator = new DefaultPortAllocator();
         Agent agent1 = Agent.igniteOrchestrator(group, portAllocator);
         Agent agent2 = Agent.ignite(group, "client-job", portAllocator, Collections.singleton(agent1.getAgentID().getAddress().toString()))) {

      final AgentID agentID1 = agent1.getAgentID();
      int port1 = agentID1.getPort();

      final AgentID agentID2 = agent2.getAgentID();
      int port2 = agentID2.getPort();

      assertFalse(agentID1.isIgniteFree());
      assertFalse(agentID2.isIgniteFree());

      assertEquals(Agent.AGENT_TYPE_ORCHESTRATOR + "#" + PidUtil.getMyPid() + "@" + IpUtils.getHostName() + "#" + port1, agentID1.toString());
      assertEquals("client-job#" + PidUtil.getMyPid() + "@" + IpUtils.getHostName() + "#" + port2, agentID2.toString());

      final Collection<AgentID> nodes = agent1.getIgnite().cluster().forAttribute("angela.group", agent1.getGroupId().toString()).nodes()
          .stream()
          .map(clusterNode -> AgentID.valueOf(clusterNode.attribute("angela.nodeName")))
          .collect(toList());

      assertEquals(2, nodes.size());

      assertTrue(nodes.contains(agentID1));
      assertTrue(nodes.contains(agentID2));
    }
  }

  @Test
  public void testIgniteCom() {
    UUID group = UUID.randomUUID();
    try (DefaultPortAllocator portAllocator = new DefaultPortAllocator();
         Agent agent1 = Agent.igniteOrchestrator(group, portAllocator);
         Agent agent2 = Agent.ignite(group, "two", portAllocator, Collections.singleton(agent1.getAgentID().getAddress().toString()))) {

      final Ignite ignite1 = agent1.getIgnite();
      final ClusterGroup clusterGroup = ignite1.cluster().forAttribute("angela.group", group.toString());
      assertEquals(2, clusterGroup.nodes().size());
      ignite1.compute(clusterGroup).broadcast(() -> AgentIT.counter.incrementAndGet());
      assertEquals(2, counter.get());

      // avoids: java: auto-closeable resource agent2 is never referenced in body of corresponding try statement
      assertNotNull(agent2);
    }
  }

  public static AtomicInteger counter = new AtomicInteger();
}
