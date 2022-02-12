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
package org.terracotta.angela.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terracotta.angela.agent.Agent;
import org.terracotta.angela.agent.AgentController;
import org.terracotta.angela.agent.com.Executor;
import org.terracotta.angela.agent.com.IgniteFreeExecutor;
import org.terracotta.angela.agent.com.IgniteLocalExecutor;
import org.terracotta.angela.agent.com.IgniteSshRemoteExecutor;
import org.terracotta.angela.client.config.ConfigurationContext;
import org.terracotta.angela.client.config.ConfigurationContextVisitor;
import org.terracotta.angela.client.config.TsaConfigurationContext;
import org.terracotta.angela.client.config.VoterConfigurationContext;
import org.terracotta.angela.common.TerracottaVoter;
import org.terracotta.angela.common.net.DefaultPortAllocator;
import org.terracotta.angela.common.net.PortAllocator;
import org.terracotta.angela.common.tcconfig.TerracottaServer;
import org.terracotta.angela.common.topology.Topology;

import java.io.Closeable;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 *
 */
public class AngelaOrchestrator implements Closeable {
  private static final Logger logger = LoggerFactory.getLogger(AngelaOrchestrator.class);

  private final Agent mainAgent;
  private final AgentController agentController;
  private final Executor executor;
  private final PortAllocator portAllocator;

  private AngelaOrchestrator(Agent mainAgent, Executor executor, PortAllocator portAllocator) {
    this.mainAgent = mainAgent;
    this.executor = executor;
    this.portAllocator = portAllocator;
    this.agentController = new AgentController(mainAgent.getAgentID(), portAllocator);

    // because we need to be able to access statically the controller from ignite closure
    // when they are executed on our local ignite node, or locally by the overriding layer
    // when in local mode
    AgentController.setUniqueInstance(agentController);
  }

  public Executor getExecutor() {
    return executor;
  }

  public PortAllocator getPortAllocator() {
    return portAllocator;
  }

  public ClusterFactory newClusterFactory(String idPrefix, ConfigurationContext configurationContext) {

    // ensure we allocate some ports to the nodes in the config
    configurationContext.visit(new ConfigurationContextVisitor() {
      @Override
      public void visit(TsaConfigurationContext tsaConfigurationContext) {
        Topology topology = tsaConfigurationContext.getTopology();
        if (topology != null) {
          logger.trace("Allocating ports for servers...");
          topology.init(portAllocator);
        }
      }

      @Override
      public void visit(VoterConfigurationContext voterConfigurationContext) {
        for (TerracottaVoter terracottaVoter : voterConfigurationContext.getTerracottaVoters()) {
          final List<String> hostPorts = terracottaVoter.getHostPorts();
          final List<String> serverNames = terracottaVoter.getServerNames();
          if (hostPorts.isEmpty() && serverNames.isEmpty()) {
            throw new IllegalArgumentException("Voter incorrectly configured: missing hosts/ports or server names");
          }
          if (hostPorts.isEmpty()) {
            // pickups allocated ports
            for (String serverName : serverNames) {
              hostPorts.add(configurationContext.tsa().getTopology().getServers()
                  .stream()
                  .filter(server -> server.getServerSymbolicName().getSymbolicName().equals(serverName))
                  .findFirst()
                  .map(TerracottaServer::getHostPort)
                  .orElseThrow(() -> new IllegalArgumentException("Incorrect voter configuration: server name '" + serverName + "' not found")));
            }
            logger.trace("Voter configured to connect to: " + hostPorts);
          }
        }
      }
    });

    return new ClusterFactory(executor, portAllocator, idPrefix, configurationContext);
  }

  @Override
  public void close() {
    try {
      executor.close();
    } finally {
      try {
        mainAgent.close();
      } finally {
        try {
          portAllocator.close();
        } finally {
          AgentController.removeUniqueInstance(agentController);
        }
      }
    }
  }

  public static AngelaOrchestratorBuilder builder() {
    return new AngelaOrchestratorBuilder().igniteRemote();
  }

  public static class AngelaOrchestratorBuilder {

    private final UUID group = UUID.randomUUID();
    private PortAllocator portAllocator = new DefaultPortAllocator();
    private Supplier<Agent> agentBuilder;
    private Function<Agent, Executor> executorBuilder;
    private String mode; // just for toString()

    public AngelaOrchestratorBuilder withPortAllocator(PortAllocator portAllocator) {
      this.portAllocator = new PortAllocator() {
        @Override
        public PortReservation reserve(int portCounts) {
          return portAllocator.reserve(portCounts);
        }

        @Override
        public void close() {
          // do not close a port allocator which has been provided by the user
        }
      };
      return this;
    }

    /**
     * Local Ignite agent started, plus one per remote hostname, deployed trough SSH. Client jobs are executed on their specified hostnames.
     */
    public AngelaOrchestratorBuilder igniteRemote() {
      agentBuilder = () -> Agent.igniteOrchestrator(group, portAllocator);
      executorBuilder = agent -> new IgniteSshRemoteExecutor(agent.getGroupId(), agent.getAgentID(), agent.getIgnite());
      mode = IgniteSshRemoteExecutor.class.getSimpleName();
      return this;
    }

    /**
     * Local Ignite agent started, plus one per remote hostname, deployed trough SSH. Client jobs are executed on their specified hostnames.
     */
    public AngelaOrchestratorBuilder igniteRemote(Consumer<IgniteSshRemoteExecutor> configurator) {
      agentBuilder = () -> Agent.igniteOrchestrator(group, portAllocator);
      executorBuilder = agent -> {
        final IgniteSshRemoteExecutor executor = new IgniteSshRemoteExecutor(agent);
        configurator.accept(executor);
        return executor;
      };
      mode = IgniteSshRemoteExecutor.class.getSimpleName();
      return this;
    }

    /**
     * Only one local Ignite instance, plus eventually one per client job, for all hostnames.
     * No Ignite agents will be deployed through SSH.
     */
    public AngelaOrchestratorBuilder igniteLocal() {
      agentBuilder = () -> Agent.igniteOrchestrator(group, portAllocator);
      executorBuilder = IgniteLocalExecutor::new;
      mode = IgniteLocalExecutor.class.getSimpleName();
      return this;
    }

    /**
     * No Ignite started: everything runs withing the test JVM, even client jobs.
     */
    public AngelaOrchestratorBuilder igniteFree() {
      agentBuilder = () -> Agent.local(group);
      executorBuilder = IgniteFreeExecutor::new;
      mode = IgniteFreeExecutor.class.getSimpleName();
      return this;
    }

    public AngelaOrchestrator build() {
      final Agent agent = agentBuilder.get();
      final Executor executor = executorBuilder.apply(agent);
      return new AngelaOrchestrator(agent, executor, portAllocator);
    }

    @Override
    public String toString() {
      return mode;
    }
  }
}
