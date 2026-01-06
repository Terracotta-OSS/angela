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

import org.apache.ignite.lang.IgniteCallable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terracotta.angela.agent.AgentController;
import org.terracotta.angela.agent.com.AgentExecutor;
import org.terracotta.angela.agent.com.Executor;
import org.terracotta.angela.agent.kit.LocalKitManager;
import org.terracotta.angela.client.config.ToolConfigurationContext;
import org.terracotta.angela.client.filesystem.RemoteFolder;
import org.terracotta.angela.common.TerracottaCommandLineEnvironment;
import org.terracotta.angela.common.ToolException;
import org.terracotta.angela.common.ToolExecutionResult;
import org.terracotta.angela.common.distribution.Distribution;
import org.terracotta.angela.common.net.PortAllocator;
import org.terracotta.angela.common.tcconfig.License;
import org.terracotta.angela.common.tcconfig.SecurityRootDirectory;
import org.terracotta.angela.common.tcconfig.ServerSymbolicName;
import org.terracotta.angela.common.tcconfig.TerracottaServer;
import org.terracotta.angela.common.topology.InstanceId;
import org.terracotta.angela.common.topology.Topology;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.terracotta.angela.common.AngelaProperties.KIT_COPY;
import static org.terracotta.angela.common.AngelaProperties.KIT_INSTALLATION_DIR;
import static org.terracotta.angela.common.AngelaProperties.KIT_INSTALLATION_PATH;
import static org.terracotta.angela.common.AngelaProperties.OFFLINE;
import static org.terracotta.angela.common.AngelaProperties.SKIP_UNINSTALL;
import static org.terracotta.angela.common.AngelaProperties.getEitherOf;

public class ConfigTool implements AutoCloseable {
  private final static Logger logger = LoggerFactory.getLogger(ConfigTool.class);

  private final transient ToolConfigurationContext configContext;
  private final transient AgentExecutor executor;
  private final InstanceId instanceId;
  private final transient LocalKitManager localKitManager;
  private final transient Tsa tsa;

  ConfigTool(Executor executor, PortAllocator portAllocator, InstanceId instanceId, ToolConfigurationContext configContext, Tsa tsa) {
    this.instanceId = instanceId;
    this.executor = executor.forAgent(executor.getAgentID(configContext.getHostName()));
    this.configContext = configContext;
    this.localKitManager = new LocalKitManager(portAllocator, configContext.getDistribution());
    this.tsa = tsa;
    install();
  }

  public ToolExecutionResult executeCommand(String... arguments) {
    return executeCommand(Collections.emptyMap(), arguments);
  }

  public ToolExecutionResult executeCommand(Map<String, String> env, String... arguments) {
    logger.debug("Executing config-tool: {} on: {}", instanceId, executor.getTarget());
    return executor.execute(() -> AgentController.getInstance().configTool(instanceId, env, arguments));
  }

  /**
   * Add a stripe as part of Dynamic Scale
   *
   * @param newServers servers to add
   * @return the config tool instance
   */
  public ConfigTool addStripe(TerracottaServer... newServers) {
    if (newServers == null || newServers.length == 0) {
      throw new IllegalArgumentException("Servers list should be non-null and non-empty");
    }

    Topology topology = tsa.getTsaConfigurationContext().getTopology();
    topology.addStripe(newServers);
    for (TerracottaServer server : newServers) {
      tsa.install(server, topology);
      tsa.spawn(server);
    }

    if (newServers.length > 1) {
      List<String> command = new ArrayList<>();
      command.add("attach");
      command.add("-t");
      command.add("node");
      command.add("-d");
      command.add(newServers[0].getHostPort());
      for (int i = 1; i < newServers.length; i++) {
        command.add("-s");
        command.add(newServers[i].getHostPort());
      }

      ToolExecutionResult result = executeCommand(command.toArray(new String[0]));
      if (result.getExitStatus() != 0) {
        throw new RuntimeException("ConfigTool::executeCommand with command parameters failed with: " + result);
      }
    }

    List<String> command = new ArrayList<>();
    command.add("attach");
    command.add("-to-cluster");

    List<List<TerracottaServer>> stripes = topology.getStripes();
    TerracottaServer existingServer = stripes.get(0).get(0);
    command.add(existingServer.getHostPort());

    command.add("-stripe");
    command.add(newServers[0].getHostPort());

    ToolExecutionResult result = executeCommand(command.toArray(new String[0]));
    if (result.getExitStatus() != 0) {
      throw new ToolException("attach stripe failed", String.join(". ", result.getOutput()), result.getExitStatus());
    }
    return this;
  }

  /**
   * Remove a stripe as part of Dynamic Scale
   *
   * @param stripeIndex stripe index
   * @return the config tool instance
   */
  public ConfigTool removeStripe(int stripeIndex) {
    Topology topology = tsa.getTsaConfigurationContext().getTopology();
    List<List<TerracottaServer>> stripes = topology.getStripes();
    if (stripeIndex < -1 || stripeIndex >= stripes.size()) {
      throw new IllegalArgumentException("stripeIndex should be a non-negative integer less than stripe count");
    }

    if (stripes.size() == 1) {
      throw new IllegalArgumentException("Cannot delete the only stripe from cluster");
    }

    List<String> command = new ArrayList<>();
    command.add("detach");

    List<TerracottaServer> toDetachStripe = stripes.remove(stripeIndex);
    TerracottaServer destination;
    if (stripeIndex != 0) {
      destination = stripes.get(0).get(0);
    } else {
      destination = stripes.get(1).get(0);
    }
    command.add("-from-cluster");
    command.add(destination.getHostPort());

    command.add("-stripe");
    command.add(toDetachStripe.get(0).getHostPort());

    ToolExecutionResult result = executeCommand(command.toArray(new String[0]));
    if (result.getExitStatus() != 0) {
      throw new ToolException("detach stripe failed", String.join(". ", result.getOutput()), result.getExitStatus());
    }

    topology.removeStripe(stripeIndex);
    return this;
  }

  public ConfigTool attachStripe(TerracottaServer... newServers) {
    if (newServers == null || newServers.length == 0) {
      throw new IllegalArgumentException("Servers list should be non-null and non-empty");
    }

    Topology topology = tsa.getTsaConfigurationContext().getTopology();
    topology.addStripe(newServers);
    for (TerracottaServer server : newServers) {
      tsa.install(server, topology);
      tsa.spawn(server);
    }

    if (newServers.length > 1) {
      List<String> command = new ArrayList<>();
      command.add("attach");
      command.add("-t");
      command.add("node");
      command.add("-d");
      command.add(newServers[0].getHostPort());
      for (int i = 1; i < newServers.length; i++) {
        command.add("-s");
        command.add(newServers[i].getHostPort());
      }

      ToolExecutionResult result = executeCommand(command.toArray(new String[0]));
      if (result.getExitStatus() != 0) {
        throw new RuntimeException("ConfigTool::executeCommand with command parameters failed with: " + result);
      }
    }

    List<String> command = new ArrayList<>();
    command.add("attach");
    command.add("-t");
    command.add("stripe");

    List<List<TerracottaServer>> stripes = topology.getStripes();
    TerracottaServer existingServer = stripes.get(0).get(0);
    command.add("-d");
    command.add(existingServer.getHostPort());
    for (TerracottaServer newServer : newServers) {
      command.add("-s");
      command.add(newServer.getHostPort());
    }

    ToolExecutionResult result = executeCommand(command.toArray(new String[0]));
    if (result.getExitStatus() != 0) {
      throw new ToolException("attach stripe failed", String.join(". ", result.getOutput()), result.getExitStatus());
    }
    return this;
  }

  public ConfigTool detachStripe(int stripeIndex) {
    Topology topology = tsa.getTsaConfigurationContext().getTopology();
    List<List<TerracottaServer>> stripes = topology.getStripes();
    if (stripeIndex < -1 || stripeIndex >= stripes.size()) {
      throw new IllegalArgumentException("stripeIndex should be a non-negative integer less than stripe count");
    }

    if (stripes.size() == 1) {
      throw new IllegalArgumentException("Cannot delete the only stripe from cluster");
    }

    List<String> command = new ArrayList<>();
    command.add("detach");
    command.add("-t");
    command.add("stripe");

    List<TerracottaServer> toDetachStripe = stripes.remove(stripeIndex);
    TerracottaServer destination = stripes.get(0).get(0);
    command.add("-d");
    command.add(destination.getHostPort());

    command.add("-s");
    command.add(toDetachStripe.get(0).getHostPort());

    ToolExecutionResult result = executeCommand(command.toArray(new String[0]));
    if (result.getExitStatus() != 0) {
      throw new ToolException("detach stripe failed", String.join(". ", result.getOutput()), result.getExitStatus());
    }

    topology.removeStripe(stripeIndex);
    return this;
  }

  public ConfigTool attachNode(int stripeIndex, TerracottaServer newServer) {
    Topology topology = tsa.getTsaConfigurationContext().getTopology();
    List<List<TerracottaServer>> stripes = topology.getStripes();
    if (stripeIndex < -1 || stripeIndex >= stripes.size()) {
      throw new IllegalArgumentException("stripeIndex should be a non-negative integer less than stripe count");
    }
    if (newServer == null) {
      throw new IllegalArgumentException("Server should be non-null");
    }

    topology.addServer(stripeIndex, newServer);
    tsa.install(newServer, topology);
    tsa.spawn(newServer);

    List<String> command = new ArrayList<>();
    command.add("attach");
    command.add("-t");
    command.add("node");

    TerracottaServer existingServer = stripes.get(stripeIndex).get(0);
    command.add("-d");
    command.add(existingServer.getHostPort());

    command.add("-s");
    command.add(newServer.getHostPort());

    ToolExecutionResult result = executeCommand(command.toArray(new String[0]));
    if (result.getExitStatus() != 0) {
      throw new ToolException("attach node failed", String.join(". ", result.getOutput()), result.getExitStatus());
    }
    return this;
  }

  public ConfigTool detachNode(int stripeIndex, int serverIndex) {
    Topology topology = tsa.getTsaConfigurationContext().getTopology();
    List<List<TerracottaServer>> stripes = topology.getStripes();
    if (stripeIndex < -1 || stripeIndex >= stripes.size()) {
      throw new IllegalArgumentException("stripeIndex should be a non-negative integer less than stripe count");
    }

    List<TerracottaServer> servers = stripes.remove(stripeIndex);
    if (serverIndex < -1 || serverIndex >= servers.size()) {
      throw new IllegalArgumentException("serverIndex should be a non-negative integer less than server count");
    }

    TerracottaServer toDetach = servers.remove(serverIndex);
    if (servers.size() == 0 && stripes.size() == 0) {
      throw new IllegalArgumentException("Cannot delete the only server from the cluster");
    }

    TerracottaServer destination;
    if (stripes.size() != 0) {
      destination = stripes.get(0).get(0);
    } else {
      destination = servers.get(0);
    }

    List<String> command = new ArrayList<>();
    command.add("detach");
    command.add("-t");
    command.add("node");
    command.add("-d");
    command.add(destination.getHostPort());
    command.add("-s");
    command.add(toDetach.getHostPort());

    ToolExecutionResult result = executeCommand(command.toArray(new String[0]));
    if (result.getExitStatus() != 0) {
      throw new ToolException("detach node failed", String.join(". ", result.getOutput()), result.getExitStatus());
    }

    topology.removeServer(stripeIndex, serverIndex);
    return this;
  }

  public ConfigTool attachAll() {
    Topology topology = tsa.getTsaConfigurationContext().getTopology();
    if (topology.isNetDisruptionEnabled()) {
      for (TerracottaServer terracottaServer : topology.getServers()) {
        setClientToServerDisruptionLinks(terracottaServer);
      }
    }
    List<List<TerracottaServer>> stripes = topology.getStripes();

    for (List<TerracottaServer> stripe : stripes) {
      if (stripe.size() > 1) {
        // Attach all servers in a stripe to form individual stripes
        for (int i = 1; i < stripe.size(); i++) {
          List<String> command = new ArrayList<>();
          command.add("attach");
          command.add("-t");
          command.add("node");
          command.add("-d");
          command.add(stripe.get(0).getHostPort());
          command.add("-s");
          command.add(stripe.get(i).getHostPort());

          ToolExecutionResult result = executeCommand(command.toArray(new String[0]));
          if (result.getExitStatus() != 0) {
            throw new ToolException("attach failed", String.join(". ", result.getOutput()), result.getExitStatus());
          }
        }
      }
    }

    if (stripes.size() > 1) {
      for (int i = 1; i < stripes.size(); i++) {
        // Attach all stripes together to form the cluster
        List<String> command = new ArrayList<>();
        command.add("attach");
        command.add("-t");
        command.add("stripe");
        command.add("-d");
        command.add(stripes.get(0).get(0).getHostPort());

        List<TerracottaServer> stripe = stripes.get(i);
        command.add("-s");
        command.add(stripe.get(0).getHostPort());

        ToolExecutionResult result = executeCommand(command.toArray(new String[0]));
        if (result.getExitStatus() != 0) {
          throw new RuntimeException("ConfigTool::executeCommand with command parameters failed with: " + result);
        }
      }
    }

    if (topology.isNetDisruptionEnabled()) {
      for (int i = 1; i <= stripes.size(); ++i) {
        if (stripes.get(i - 1).size() > 1) {
          setServerToServerDisruptionLinks(i, stripes.get(i - 1).size());
        }
      }
    }
    return this;
  }

  public ConfigTool activate(Map<String, String> env) {
    TerracottaCommandLineEnvironment tcEnv = configContext.getCommandLineEnv();
    SecurityRootDirectory securityRootDirectory = configContext.getSecurityRootDirectory();
    License license = tsa.getTsaConfigurationContext().getLicense();
    TerracottaServer terracottaServer = tsa.getTsaConfigurationContext().getTopology().getServers().get(0);
    logger.info("Activating cluster from {}", terracottaServer.getHostName());
    String clusterName = tsa.getTsaConfigurationContext().getClusterName();
    if (clusterName == null) {
      clusterName = instanceId.toString();
    }
    List<String> args = new ArrayList<>(Arrays.asList("activate", "-n", clusterName, "-s", terracottaServer.getHostPort()));
    logger.debug("Executing config-tool activate: {} on: {}", instanceId, executor.getTarget());
    ToolExecutionResult result = executor.execute(() -> AgentController.getInstance().activate(instanceId, license, securityRootDirectory, tcEnv, env, args));
    if (result.getExitStatus() != 0) {
      throw new IllegalStateException("Failed to execute config-tool activate:\n" + result);
    }
    return this;
  }

  public ConfigTool activate() {
    return activate(Collections.emptyMap());
  }

  public ConfigTool install() {
    Distribution distribution = configContext.getDistribution();
    License license = tsa.getTsaConfigurationContext().getLicense();
    TerracottaCommandLineEnvironment tcEnv = configContext.getCommandLineEnv();
    SecurityRootDirectory securityRootDirectory = configContext.getSecurityRootDirectory();

    String kitInstallationPath = getEitherOf(KIT_INSTALLATION_DIR, KIT_INSTALLATION_PATH);
    localKitManager.setupLocalInstall(license, kitInstallationPath, OFFLINE.getBooleanValue(), tcEnv);

    logger.info("Installing config-tool: {} on: {}", instanceId, executor.getTarget());
    final String hostName = configContext.getHostName();
    final String kitInstallationName = localKitManager.getKitInstallationName();
    final IgniteCallable<Boolean> callable = () -> AgentController.getInstance().installConfigTool(instanceId, hostName, distribution, license, kitInstallationName, securityRootDirectory, tcEnv, kitInstallationPath);
    boolean isRemoteInstallationSuccessful = executor.execute(callable);
    if (!isRemoteInstallationSuccessful && (kitInstallationPath == null || !KIT_COPY.getBooleanValue())) {
      try {
        executor.uploadKit(instanceId, distribution, kitInstallationName, localKitManager.getKitInstallationPath());
        executor.execute(callable);
      } catch (Exception e) {
        throw new RuntimeException("Cannot upload kit to " + hostName, e);
      }
    }
    return this;
  }

  public ConfigTool uninstall() {
    logger.info("Uninstalling config-tool: {} from: {}", instanceId, executor.getTarget());
    final Distribution distribution = configContext.getDistribution();
    final String hostName = configContext.getHostName();
    final String kitInstallationName = localKitManager.getKitInstallationName();
    executor.execute(() -> AgentController.getInstance().uninstallConfigTool(instanceId, distribution, hostName, kitInstallationName));
    return this;
  }

  public ConfigTool setClientToServerDisruptionLinks(TerracottaServer terracottaServer) {
    // Disabling client redirection from passive to current active.
    List<String> arguments = new ArrayList<>();
    String property = "stripe.1.node.1.tc-properties." + "l2.l1redirect.enabled=false";
    arguments.add("set");
    arguments.add("-s");
    arguments.add(terracottaServer.getHostPort());
    arguments.add("-c");
    arguments.add(property);
    ToolExecutionResult executionResult = executeCommand(arguments.toArray(new String[0]));
    if (executionResult.getExitStatus() != 0) {
      throw new RuntimeException("ConfigTool::executeCommand with command parameters failed with: " + executionResult);
    }

    // Creating disruption links for client to server disruption
    Map<ServerSymbolicName, Integer> proxyMap = tsa.updateToProxiedPorts();
    int proxyPort = proxyMap.get(terracottaServer.getServerSymbolicName());
    String publicHostName = "stripe.1.node.1.public-hostname=" + terracottaServer.getHostName();
    String publicPort = "stripe.1.node.1.public-port=" + proxyPort;

    List<String> args = new ArrayList<>();
    args.add("set");
    args.add("-s");
    args.add(terracottaServer.getHostPort());
    args.add("-c");
    args.add(publicHostName);
    args.add("-c");
    args.add(publicPort);

    executionResult = executeCommand(args.toArray(new String[0]));
    if (executionResult.getExitStatus() != 0) {
      throw new RuntimeException("ConfigTool::executeCommand with command parameters failed with: " + executionResult);
    }
    return this;
  }

  public ConfigTool setServerToServerDisruptionLinks(int stripeId, int size) {
    List<TerracottaServer> stripeServerList = tsa.getTsaConfigurationContext()
            .getTopology()
            .getStripes()
            .get(stripeId - 1);
    for (int j = 0; j < size; ++j) {
      TerracottaServer server = stripeServerList.get(j);
      Map<ServerSymbolicName, Integer> proxyGroupPortMapping = tsa.getProxyGroupPortsForServer(server);
      int nodeId = j + 1;
      StringBuilder propertyBuilder = new StringBuilder();
      propertyBuilder.append("stripe.")
              .append(stripeId)
              .append(".node.")
              .append(nodeId)
              .append(".tc-properties.test-proxy-group-port=");
      propertyBuilder.append("\"");
      for (Map.Entry<ServerSymbolicName, Integer> entry : proxyGroupPortMapping.entrySet()) {
        propertyBuilder.append(entry.getKey().getSymbolicName());
        propertyBuilder.append("->");
        propertyBuilder.append(entry.getValue());
        propertyBuilder.append("#");
      }
      propertyBuilder.deleteCharAt(propertyBuilder.lastIndexOf("#"));
      propertyBuilder.append("\"");

      ToolExecutionResult executionResult = executeCommand("set", "-s", server.getHostPort(), "-c", propertyBuilder.toString());
      if (executionResult.getExitStatus() != 0) {
        throw new RuntimeException("ConfigTool::executeCommand with command parameters failed with: " + executionResult);
      }
    }
    return this;
  }

  public RemoteFolder browse(String root) {
    logger.debug("Browsing config-tool: {} on: {}", instanceId, executor.getTarget());
    String path = executor.execute(() -> AgentController.getInstance().getConfigToolInstallPath(instanceId));
    return new RemoteFolder(executor, path, root);
  }

  @Override
  public void close() {
    if (!SKIP_UNINSTALL.getBooleanValue()) {
      uninstall();
    }
  }
}