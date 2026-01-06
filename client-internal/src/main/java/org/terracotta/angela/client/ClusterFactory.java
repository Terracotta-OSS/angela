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
package org.terracotta.angela.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terracotta.angela.agent.com.AgentID;
import org.terracotta.angela.agent.com.Executor;
import org.terracotta.angela.client.config.ClientArrayConfigurationContext;
import org.terracotta.angela.client.config.ConfigurationContext;
import org.terracotta.angela.client.config.MonitoringConfigurationContext;
import org.terracotta.angela.client.config.TmsConfigurationContext;
import org.terracotta.angela.client.config.ToolConfigurationContext;
import org.terracotta.angela.client.config.TsaConfigurationContext;
import org.terracotta.angela.client.config.VoterConfigurationContext;
import org.terracotta.angela.client.config.WebMIsConfigurationContext;
import org.terracotta.angela.common.cluster.Cluster;
import org.terracotta.angela.common.metrics.HardwareMetric;
import org.terracotta.angela.common.metrics.MonitoringCommand;
import org.terracotta.angela.common.net.PortAllocator;
import org.terracotta.angela.common.topology.InstanceId;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.stream.Collectors.toList;

public class ClusterFactory implements AutoCloseable {
  private static final Logger logger = LoggerFactory.getLogger(ClusterFactory.class);

  private static final String TSA = "tsa";
  private static final String TMS = "tms";
  private static final String CLIENT_ARRAY = "clientArray";
  private static final String MONITOR = "monitor";
  private static final String CLUSTER_TOOL = "clusterTool";
  private static final String IMPORT_TOOL = "importTool";
  private static final String CONFIG_TOOL = "configTool";
  private static final String VOTER = "voter";
  private static final String WEBMIS = "webMIs";
  private static final DateTimeFormatter PATH_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-hhmmssSSS");

  private final List<AutoCloseable> controllers = new ArrayList<>();
  private final Executor executor;
  private final PortAllocator portAllocator;
  private final String idPrefix;
  private final AtomicInteger instanceIndex;
  private final ConfigurationContext configurationContext;

  private InstanceId monitorInstanceId;

  ClusterFactory(Executor executor, PortAllocator portAllocator, String idPrefix, ConfigurationContext configurationContext) {
    // Using UTC to have consistent layout even in case of timezone skew between client and server.
    this.idPrefix = idPrefix + "-" + LocalDateTime.now(ZoneId.of("UTC")).format(PATH_FORMAT);
    this.instanceIndex = new AtomicInteger();
    this.configurationContext = configurationContext;
    this.executor = executor;
    this.portAllocator = portAllocator;
  }

  private synchronized InstanceId init(String type, Collection<String> hostnames) {
    if (hostnames.isEmpty()) {
      throw new IllegalArgumentException("Cannot initialize with 0 server");
    }
    if (hostnames.stream().anyMatch(Objects::isNull)) {
      throw new IllegalArgumentException("Cannot initialize with a null server name");
    }

    // ensure agents are started on the configured hostnames
    final List<AgentID> agentIDS = hostnames.stream()
            .filter(hostname -> !executor.findAgentID(hostname).isPresent()) // no agent built for a hostname ?
            .map(executor::startRemoteAgent) // then try spawn one
            .filter(Optional::isPresent) //
            .map(Optional::get)
            .collect(toList());

    if (!agentIDS.isEmpty()) {
      logger.info("Spawned agents: {}", agentIDS);
    }

    return new InstanceId(idPrefix + "-" + instanceIndex.getAndIncrement(), type);
  }

  public Cluster cluster() {
    return executor.getCluster();
  }

  public Tsa tsa() {
    TsaConfigurationContext tsaConfigurationContext = configurationContext.tsa();
    if (tsaConfigurationContext == null) {
      throw new IllegalArgumentException("tsa() configuration missing in the ConfigurationContext");
    }
    InstanceId instanceId = init(TSA, tsaConfigurationContext.getTopology().getServersHostnames());

    Tsa tsa = new Tsa(executor, portAllocator, instanceId, tsaConfigurationContext);
    controllers.add(tsa);
    return tsa;
  }

  public Tms tms() {
    TmsConfigurationContext tmsConfigurationContext = configurationContext.tms();
    if (tmsConfigurationContext == null) {
      throw new IllegalArgumentException("tms() configuration missing in the ConfigurationContext");
    }
    InstanceId instanceId = init(TMS, Collections.singletonList(tmsConfigurationContext.getHostName()));

    Tms tms = new Tms(executor, portAllocator, instanceId, tmsConfigurationContext);
    controllers.add(tms);
    return tms;
  }

  public ClusterTool clusterTool() {
    ToolConfigurationContext clusterToolConfigurationContext = configurationContext.clusterTool();
    if (clusterToolConfigurationContext == null) {
      throw new IllegalArgumentException("clusterTool() configuration missing in the ConfigurationContext");
    }
    InstanceId instanceId = init(CLUSTER_TOOL, Collections.singleton(clusterToolConfigurationContext.getHostName()));
    Tsa tsa = controllers.stream()
            .filter(controller -> controller instanceof Tsa)
            .map(autoCloseable -> (Tsa) autoCloseable)
            .findAny()
            .orElseThrow(() -> new IllegalStateException("Tsa should be defined before cluster tool in ConfigurationContext"));
    ClusterTool clusterTool = new ClusterTool(executor, portAllocator, instanceId, clusterToolConfigurationContext, tsa);
    controllers.add(clusterTool);
    return clusterTool;
  }

  public ImportTool importTool() {
    ToolConfigurationContext importToolConfigurationContext = configurationContext.importTool();
    if (importToolConfigurationContext == null) {
      throw new IllegalArgumentException("importTool() configuration missing in the ConfigurationContext");
    }
    InstanceId instanceId = init(IMPORT_TOOL, Collections.singleton(importToolConfigurationContext.getHostName()));
    Tsa tsa = controllers.stream()
            .filter(controller -> controller instanceof Tsa)
            .map(autoCloseable -> (Tsa) autoCloseable)
            .findAny()
            .orElseThrow(() -> new IllegalStateException("Tsa should be defined before import tool in ConfigurationContext"));
    ImportTool importTool = new ImportTool(executor, portAllocator, instanceId, importToolConfigurationContext, tsa);
    controllers.add(importTool);
    return importTool;
  }

  public ConfigTool configTool() {
    ToolConfigurationContext configToolConfigurationContext = configurationContext.configTool();
    if (configToolConfigurationContext == null) {
      throw new IllegalArgumentException("configTool() configuration missing in the ConfigurationContext");
    }
    InstanceId instanceId = init(CONFIG_TOOL, Collections.singleton(configToolConfigurationContext.getHostName()));
    Tsa tsa = controllers.stream()
            .filter(controller -> controller instanceof Tsa)
            .map(autoCloseable -> (Tsa) autoCloseable)
            .findAny()
            .orElseThrow(() -> new IllegalStateException("Tsa should be defined before config tool in ConfigurationContext"));
    ConfigTool configTool = new ConfigTool(executor, portAllocator, instanceId, configToolConfigurationContext, tsa);
    controllers.add(configTool);
    return configTool;
  }

  public WebMIs webMIs() {
    final WebMIsConfigurationContext webMIsConfigurationContext = configurationContext.webMIs();
    if (webMIsConfigurationContext == null) {
      throw new IllegalArgumentException("webMIs() configuration missing in the ConfigurationContext");
    }
    InstanceId instanceId = init(WEBMIS, Collections.singletonList(webMIsConfigurationContext.getTopology().getWebMIsServer().getHostName()));

    WebMIs webMIs = new WebMIs(executor, portAllocator, instanceId, webMIsConfigurationContext);
    controllers.add(webMIs);
    return webMIs;
  }

  public Voter voter() {
    VoterConfigurationContext voterConfigurationContext = configurationContext.voter();
    if (voterConfigurationContext == null) {
      throw new IllegalArgumentException("voter() configuration missing in the ConfigurationContext");
    }
    InstanceId instanceId = init(VOTER, voterConfigurationContext.getHostNames());
    Voter voter = new Voter(executor, portAllocator, instanceId, voterConfigurationContext);
    controllers.add(voter);
    return voter;
  }

  public ClientArray firstClientArray() {
    return clientArray(0);
  }

  public ClientArray clientArray(int idx) {
    ClientArrayConfigurationContext clientArrayConfigurationContext = configurationContext.clientArray(idx);
    init(CLIENT_ARRAY, clientArrayConfigurationContext.getClientArrayTopology().getClientHostnames());

    ClientArray clientArray = new ClientArray(executor, portAllocator, () -> init(CLIENT_ARRAY, clientArrayConfigurationContext.getClientArrayTopology().getClientHostnames()), clientArrayConfigurationContext);
    controllers.add(clientArray);
    return clientArray;
  }

  public List<ClientArray> clientArray() {
    return configurationContext.clientArray().stream()
            .map(clientArrayConfigurationContext -> {
              init(CLIENT_ARRAY, clientArrayConfigurationContext.getClientArrayTopology().getClientHostnames());

              ClientArray clientArray = new ClientArray(executor, portAllocator,
                      () -> init(CLIENT_ARRAY, clientArrayConfigurationContext.getClientArrayTopology()
                              .getClientHostnames()), clientArrayConfigurationContext);
              controllers.add(clientArray);
              return clientArray;
            })
            .collect(toList());
  }

  public ClusterMonitor monitor() {
    MonitoringConfigurationContext monitoringConfigurationContext = configurationContext.monitoring();
    if (monitoringConfigurationContext == null) {
      throw new IllegalArgumentException("monitoring() configuration missing in the ConfigurationContext");
    }
    Map<HardwareMetric, MonitoringCommand> commands = monitoringConfigurationContext.commands();
    Set<String> hostnames = configurationContext.allHostnames();

    if (monitorInstanceId == null) {
      monitorInstanceId = init(MONITOR, hostnames);
      ClusterMonitor clusterMonitor = new ClusterMonitor(executor, monitorInstanceId, hostnames, commands);
      controllers.add(clusterMonitor);
      return clusterMonitor;
    } else {
      return new ClusterMonitor(executor, monitorInstanceId, hostnames, commands);
    }
  }

  @Override
  public void close() {
    boolean interrupt = false;
    for (AutoCloseable controller : controllers) {
      try {
        controller.close();
      } catch (Exception e) {
        logger.error("close() error: " + e.getMessage(), e);
        if (e instanceof InterruptedException) {
          interrupt = true;
        }
      }
    }
    controllers.clear();
    monitorInstanceId = null;
    if (interrupt) {
      Thread.currentThread().interrupt();
    }
  }
}
