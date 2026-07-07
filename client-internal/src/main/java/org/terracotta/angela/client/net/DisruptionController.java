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
import org.terracotta.angela.agent.com.Executor;
import org.terracotta.angela.common.net.DisruptionProvider;
import org.terracotta.angela.common.net.DisruptionProviderFactory;
import org.terracotta.angela.common.net.Disruptor;
import org.terracotta.angela.common.net.PortAllocator;
import org.terracotta.angela.common.provider.ConfigurationManager;
import org.terracotta.angela.common.provider.DynamicConfigManager;
import org.terracotta.angela.common.provider.TcConfigManager;
import org.terracotta.angela.common.tcconfig.ServerSymbolicName;
import org.terracotta.angela.common.tcconfig.TcConfig;
import org.terracotta.angela.common.tcconfig.TerracottaServer;
import org.terracotta.angela.common.topology.InstanceId;
import org.terracotta.angela.common.topology.Topology;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class DisruptionController implements AutoCloseable {
  private static final Logger LOGGER = LoggerFactory.getLogger(DisruptionController.class);
  private static final DisruptionProvider DISRUPTION_PROVIDER = DisruptionProviderFactory.getDefault();
  private final Executor executor;
  private final InstanceId instanceId;
  private final Topology topology;
  private final Collection<Disruptor> existingDisruptors = new ArrayList<>();
  private final Map<ServerSymbolicName, Integer> proxyTsaPorts = new HashMap<>();
  private volatile boolean closed;

  public DisruptionController(Executor executor, InstanceId instanceId, Topology topology) {
    this.executor = executor;
    this.instanceId = instanceId;
    this.topology = topology;
  }

  /**
   * Create disruptor to control traffic between all servers specified.
   * (ex: Server1 &lt;-&gt; Server2, Server2 &lt;-&gt; Server3 &amp; Server3 &lt;-&gt; Server1)
   *
   * @param servers to be disrupted
   * @return {@link ServerToServerDisruptor}
   */
  public ServerToServerDisruptor newServerToServerDisruptor(TerracottaServer... servers) {
    if (servers.length < 2) {
      throw new IllegalArgumentException("Two or more split clusters required for server to server disruption");
    }
    SplitCluster[] splitClusters = new SplitCluster[servers.length];
    for (int i = 0; i < servers.length; ++i) {
      splitClusters[i] = new SplitCluster(servers[i]);
    }
    return newServerToServerDisruptor(splitClusters);
  }


  /**
   * Create disruptor to control traffic between set of servers specified
   * (ex: [Server1] &lt;-&gt; [Server2,Server3]. No disruption between Server2 and Server3 in this example)
   *
   * @param splitClusters {@link SplitCluster}
   * @return {@link ServerToServerDisruptor}
   */
  public ServerToServerDisruptor newServerToServerDisruptor(SplitCluster... splitClusters) {
    if (!topology.isNetDisruptionEnabled()) {
      throw new IllegalArgumentException("Topology not enabled for network disruption");
    }
    if (closed) {
      throw new IllegalStateException("already closed");
    }
    if (splitClusters.length < 2) {
      throw new IllegalArgumentException("Two or more split clusters required for server to server disruption");
    }

    //
    for (SplitCluster splitCluster : splitClusters) {
      if (splitCluster.getServers().isEmpty()) {
        throw new IllegalArgumentException("Empty split cluster " + splitCluster);
      }
    }

    //validate for any duplicate server
    for (int i = 0; i < splitClusters.length; ++i) {
      for (int j = i + 1; j < splitClusters.length; ++j) {
        SplitCluster cluster1 = splitClusters[i];
        SplitCluster cluster2 = splitClusters[j];
        if (!Collections.disjoint(cluster1.getServers(), cluster2.getServers())) {
          throw new IllegalArgumentException("Duplicate servers found in split clusters { " + cluster1 + " } and { " + cluster2 + " }");
        }
      }
    }

    LOGGER.debug("new disruptor for {}", (Object) splitClusters);
    //compute servers to be linked for disruption based on input split clusters
    final Map<ServerSymbolicName, Collection<ServerSymbolicName>> linkedServers = new HashMap<>();
    for (int i = 0; i < splitClusters.length; ++i) {
      for (int j = i + 1; j < splitClusters.length; ++j) {
        SplitCluster cluster1 = splitClusters[i];
        SplitCluster cluster2 = splitClusters[j];
        for (ServerSymbolicName server : cluster1.getServers()) {
          linkedServers.computeIfAbsent(server, key -> new ArrayList<>()).addAll(cluster2.getServers());
        }
        for (ServerSymbolicName server : cluster2.getServers()) {
          linkedServers.computeIfAbsent(server, key -> new ArrayList<>()).addAll(cluster1.getServers());
        }
      }
    }


    //validate if any server to be linked is already linked
    final Set<ServerSymbolicName> alreadyLinkedServers = new HashSet<>();
    for (Map.Entry<ServerSymbolicName, Collection<ServerSymbolicName>> entry : linkedServers.entrySet()) {
      ServerSymbolicName server = entry.getKey();
      Collection<ServerSymbolicName> newConnected = entry.getValue();
      for (Disruptor disruption : existingDisruptors) {
        if (disruption instanceof ServerToServerDisruptor) {
          ServerToServerDisruptor serverToServerDisruptor = (ServerToServerDisruptor) disruption;
          Collection<ServerSymbolicName> alreadyConnected = serverToServerDisruptor.getLinkedServers().get(server);
          if (alreadyConnected != null && !Collections.disjoint(alreadyConnected, newConnected)) {
            alreadyLinkedServers.add(server);
          }
        }

      }
    }
    if (alreadyLinkedServers.size() > 0) {
      throw new IllegalArgumentException("Servers are already linked:" + alreadyLinkedServers);
    }


    ServerToServerDisruptor disruption = new ServerToServerDisruptor(executor, instanceId, topology, linkedServers, existingDisruptors::remove);
    existingDisruptors.add(disruption);
    LOGGER.debug("created disruptor {}", disruption);
    return disruption;
  }

  /**
   * Create a cross-cluster disruptor that controls the network links between source servers
   * in this topology and their peers in a remote topology.
   * If no servers are specified, all source servers e.g. replica's in the topology are used automatically.
   * Each source server must be configured for cross-cluster connection (e.g., a replica that connects to a relay).
   * The peer information is derived from each source server's configuration.
   * @param sourceServers The servers in this topology that initiate cross-cluster connections to its peers.
   *                      If empty, all source e.g. replica servers in the topology are used.
   * @return {@link CrossClusterServerToServerDisruptor}
   */
  public CrossClusterServerToServerDisruptor newCrossClusterServerToServerDisruptor(TerracottaServer... sourceServers) {
    if (!topology.isNetDisruptionEnabled()) {
      throw new IllegalArgumentException("Topology not enabled for network disruption");
    }
    if (closed) {
      throw new IllegalStateException("already closed");
    }
    // If no servers specified, it will discover all the replica's in the topology and disrupt all with its peers.
    if (sourceServers.length == 0) {
      sourceServers = topology.getServers().stream()
          .filter(TerracottaServer::isReplica)
          .toArray(TerracottaServer[]::new);
      if (sourceServers.length == 0) {
        throw new IllegalArgumentException("No replica servers found in the topology");
      }
    }
    for (TerracottaServer sourceServer : sourceServers) {
      if (!sourceServer.isReplica() || sourceServer.getRelayGroupPort() <= 0) {
        throw new IllegalArgumentException("Server " + sourceServer.getServerSymbolicName() + " is not configured for cross-cluster connection");
      }
    }
    CrossClusterServerToServerDisruptor disruption = new CrossClusterServerToServerDisruptor(executor, instanceId, sourceServers);
    existingDisruptors.add(disruption);
    return disruption;
  }

  /**
   * Create client to server disruptor for controlling traffic between
   * client like DatasetManager &amp; CacheManager and servers. This needs to
   * be created before initializing DatasetManager or CacheManager and use
   * connection URI obtained from this disruptor {@link ClientToServerDisruptor#uri()}
   *
   * @return {@link ClientToServerDisruptor}
   */
  public ClientToServerDisruptor newClientToServerDisruptor() {
    if (!topology.isNetDisruptionEnabled()) {
      throw new IllegalArgumentException("Topology not enabled for network disruption");
    }
    if (closed) {
      throw new IllegalStateException("already closed");
    }

    LOGGER.debug("creating new client to servers disruption");
    Optional<Disruptor> disruptor = existingDisruptors.stream()
        .filter(d -> d instanceof ClientToServerDisruptor)
        .findAny();
    if (DISRUPTION_PROVIDER.isProxyBased() && disruptor.isPresent()) {
      //make sure single disruptor serves all clients
      return (ClientToServerDisruptor) disruptor.get();
    } else {
      ClientToServerDisruptor newDisruptor = new ClientToServerDisruptor(topology, existingDisruptors::remove, proxyTsaPorts);
      existingDisruptors.add(newDisruptor);
      return newDisruptor;
    }
  }


  @Override
  public void close() {
    LOGGER.debug("closing disruption controller");
    ArrayList<Disruptor> copy = new ArrayList<>(existingDisruptors);
    for (Disruptor disruption : copy) {
      disruption.close();
    }
    closed = true;
  }

  public Map<ServerSymbolicName, Integer> updateTsaPortsWithProxy(Topology topology, PortAllocator portAllocator) {
    Map<ServerSymbolicName, Integer> proxyMap = new HashMap<>();
    if (proxyTsaPorts.isEmpty()) {
      if (DISRUPTION_PROVIDER.isProxyBased()) {
        ConfigurationManager configurationProvider = topology.getConfigurationManager();
        if (configurationProvider instanceof TcConfigManager) {
          TcConfigManager tcConfigProvider = (TcConfigManager) configurationProvider;
          List<TcConfig> configs = tcConfigProvider.getTcConfigs();
          for (TcConfig config : configs) {
            TcConfig copy = config.copy();
            proxyTsaPorts.putAll(copy.retrieveTsaPorts(true, portAllocator));
            proxyMap.putAll(proxyTsaPorts);
          }
        } else {
          DynamicConfigManager dynamicConfigManager = (DynamicConfigManager) configurationProvider;
          List<TerracottaServer> servers = dynamicConfigManager.getServers();
          PortAllocator.PortReservation reservation = portAllocator.reserve(servers.size());
          for (TerracottaServer terracottaServer : servers) {
            proxyTsaPorts.put(terracottaServer.getServerSymbolicName(), reservation.next());
          }
          proxyMap.putAll(proxyTsaPorts);
        }
        ClientToServerDisruptor newDisruptor = new ClientToServerDisruptor(topology, existingDisruptors::remove, proxyTsaPorts);
        existingDisruptors.add(newDisruptor);
      }
    } else {
      proxyMap.putAll(proxyTsaPorts);
    }
    return proxyMap;
  }

  public Map<ServerSymbolicName, Integer> getProxyTsaPorts() {
    return proxyTsaPorts;
  }

}
