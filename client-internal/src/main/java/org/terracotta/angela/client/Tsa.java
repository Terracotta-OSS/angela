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

import org.apache.ignite.lang.IgniteCallable;
import org.apache.ignite.lang.IgniteRunnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terracotta.angela.agent.AgentController;
import org.terracotta.angela.agent.com.AgentExecutor;
import org.terracotta.angela.agent.com.AgentID;
import org.terracotta.angela.agent.com.Executor;
import org.terracotta.angela.agent.kit.LocalKitManager;
import org.terracotta.angela.client.config.TsaConfigurationContext;
import org.terracotta.angela.client.filesystem.RemoteFolder;
import org.terracotta.angela.client.net.DisruptionController;
import org.terracotta.angela.common.TerracottaCommandLineEnvironment;
import org.terracotta.angela.common.TerracottaServerState;
import org.terracotta.angela.common.distribution.Distribution;
import org.terracotta.angela.common.net.PortAllocator;
import org.terracotta.angela.common.provider.ConfigurationManager;
import org.terracotta.angela.common.provider.TcConfigManager;
import org.terracotta.angela.common.tcconfig.License;
import org.terracotta.angela.common.tcconfig.ServerSymbolicName;
import org.terracotta.angela.common.tcconfig.TcConfig;
import org.terracotta.angela.common.tcconfig.TerracottaServer;
import org.terracotta.angela.common.topology.InstanceId;
import org.terracotta.angela.common.topology.Topology;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static java.util.EnumSet.of;
import static org.terracotta.angela.client.config.TsaConfigurationContext.TerracottaCommandLineEnvironmentKeys.SERVER_START_PREFIX;
import static org.terracotta.angela.common.AngelaProperties.KIT_COPY;
import static org.terracotta.angela.common.AngelaProperties.KIT_INSTALLATION_DIR;
import static org.terracotta.angela.common.AngelaProperties.KIT_INSTALLATION_PATH;
import static org.terracotta.angela.common.AngelaProperties.OFFLINE;
import static org.terracotta.angela.common.AngelaProperties.SKIP_UNINSTALL;
import static org.terracotta.angela.common.AngelaProperties.getEitherOf;
import static org.terracotta.angela.common.TerracottaServerState.STARTED_AS_ACTIVE;
import static org.terracotta.angela.common.TerracottaServerState.STARTED_AS_PASSIVE;
import static org.terracotta.angela.common.TerracottaServerState.STARTED_IN_DIAGNOSTIC_MODE;
import static org.terracotta.angela.common.TerracottaServerState.START_SUSPENDED;
import static org.terracotta.angela.common.TerracottaServerState.STOPPED;

/**
 * @author Aurelien Broszniowski
 */
public class Tsa implements AutoCloseable {

  private final static Logger logger = LoggerFactory.getLogger(Tsa.class);

  private final transient Executor executor;
  private final InstanceId instanceId;
  private final transient DisruptionController disruptionController;
  private final transient TsaConfigurationContext tsaConfigurationContext;
  private final transient LocalKitManager localKitManager;
  private final transient PortAllocator portAllocator;
  private boolean closed = false;

  Tsa(Executor executor, PortAllocator portAllocator, InstanceId instanceId, TsaConfigurationContext tsaConfigurationContext) {
    this.portAllocator = portAllocator;
    this.tsaConfigurationContext = tsaConfigurationContext;
    this.instanceId = instanceId;
    this.executor = executor;
    this.disruptionController = new DisruptionController(executor, instanceId, tsaConfigurationContext.getTopology());
    this.localKitManager = new LocalKitManager(portAllocator, tsaConfigurationContext.getTopology().getDistribution());
    installAll();
  }

  public TsaConfigurationContext getTsaConfigurationContext() {
    return tsaConfigurationContext;
  }

  public DisruptionController getDisruptionController() {
    return disruptionController;
  }

  public PortAllocator getPortAllocator() {
    return portAllocator;
  }

  public InstanceId getInstanceId() {
    return instanceId;
  }

  public String licensePath(TerracottaServer terracottaServer) {
    TerracottaServerState terracottaServerState = getState(terracottaServer);
    if (terracottaServerState == null) {
      throw new IllegalStateException("Cannot get license path: server " + terracottaServer.getServerSymbolicName() + " has not been installed");
    }
    final AgentID agentID = executor.getAgentID(terracottaServer.getHostName());
    logger.debug("Reading license path of TSA: {} from: {}", instanceId, agentID);
    return executor.execute(agentID, () -> AgentController.getInstance().getTsaLicensePath(instanceId, terracottaServer));
  }

  void installAll() {
    Topology topology = tsaConfigurationContext.getTopology();
    ConfigurationManager configurationManager = topology.getConfigurationManager();
    for (TerracottaServer terracottaServer : configurationManager.getServers()) {
      install(terracottaServer, topology);
    }
  }

  void install(TerracottaServer terracottaServer, Topology topology) {
    installWithKitManager(terracottaServer, topology, this.localKitManager);
  }

  private void installWithKitManager(TerracottaServer terracottaServer, Topology topology, LocalKitManager localKitManager) {
    // this is possible that a server gets dynamically added (DC use case)
    // so we need to ensure a port is allocated
    topology.init(portAllocator);

    TerracottaServerState terracottaServerState = getState(terracottaServer);
    if (terracottaServerState != TerracottaServerState.NOT_INSTALLED) {
      throw new IllegalStateException("Cannot install: server " + terracottaServer.getServerSymbolicName() + " in state " + terracottaServerState);
    }
    Distribution distribution = localKitManager.getDistribution();

    License license = tsaConfigurationContext.getLicense();

    String kitInstallationPath = getEitherOf(KIT_INSTALLATION_DIR, KIT_INSTALLATION_PATH);
    localKitManager.setupLocalInstall(license, kitInstallationPath, OFFLINE.getBooleanValue());
    final String kitInstallationName = localKitManager.getKitInstallationName();
    final AgentID agentID = executor.getAgentID(terracottaServer.getHostName());

    logger.info("Installing TSA: {} on: {}", instanceId, agentID);

    if (kitInstallationPath == null || KIT_COPY.getBooleanValue()) {
      // "kitInstallationPath" is either not provided (=> kit download)
      // or it is provided but we specifically ask for a kit copy
      final IgniteCallable<Boolean> installClosure = () -> AgentController.getInstance().installTsa(instanceId, terracottaServer, license, kitInstallationName, distribution, topology, null);
      boolean isRemoteInstallationSuccessful = executor.execute(agentID, installClosure);
      if (!isRemoteInstallationSuccessful) {
        try {
          logger.debug("Uploading: {} on: {}", distribution, agentID);
          executor.uploadKit(agentID, instanceId, distribution, kitInstallationName, localKitManager.getKitInstallationPath());
          executor.execute(agentID, installClosure);
        } catch (Exception e) {
          throw new RuntimeException("Cannot upload kit to " + terracottaServer.getHostName(), e);
        }
      }
    } else {
      // We are trying to reuse the "kitInstallationPath" if provided (kitInstallationPath != null)
      // To end up here, KIT_COPY should be false
      executor.execute(agentID, () -> AgentController.getInstance().installTsa(instanceId, terracottaServer, license, kitInstallationName, distribution, topology, kitInstallationPath));
    }
  }

  public Tsa upgrade(TerracottaServer server, Distribution newDistribution) {
    logger.info("Upgrading TSA: {} to: {}", server, newDistribution);
    uninstall(server);
    LocalKitManager localKitManager = new LocalKitManager(portAllocator, newDistribution);
    installWithKitManager(server, tsaConfigurationContext.getTopology(), localKitManager);
    return this;
  }

  private void uninstallAll() {
    Topology topology = tsaConfigurationContext.getTopology();
    for (TerracottaServer terracottaServer : topology.getServers()) {
      uninstall(terracottaServer);
    }
  }

  private void uninstall(TerracottaServer terracottaServer) {
    TerracottaServerState terracottaServerState = getState(terracottaServer);
    if (terracottaServerState == null) {
      return;
    }
    if (terracottaServerState != TerracottaServerState.STOPPED) {
      throw new IllegalStateException("Cannot uninstall: server " + terracottaServer.getServerSymbolicName() + " in state " + terracottaServerState);
    }

    String kitInstallationPath = getEitherOf(KIT_INSTALLATION_DIR, KIT_INSTALLATION_PATH);
    final AgentID agentID = executor.getAgentID(terracottaServer.getHostName());
    final Topology topology = tsaConfigurationContext.getTopology();
    final String kitInstallationName = localKitManager.getKitInstallationName();

    logger.info("Uninstalling TSA: {} from: {}", instanceId, agentID);

    IgniteRunnable uninstaller = () -> AgentController.getInstance().uninstallTsa(instanceId, topology, terracottaServer, kitInstallationName, kitInstallationPath);
    executor.execute(agentID, uninstaller);
  }

  public Tsa createAll(String... startUpArgs) {
    tsaConfigurationContext.getTopology().getServers().stream()
        .map(server -> CompletableFuture.runAsync(() -> create(server, startUpArgs)))
        .reduce(CompletableFuture::allOf).ifPresent(CompletableFuture::join);
    return this;
  }

  public Jcmd jcmd(TerracottaServer terracottaServer) {
    String whatFor = TsaConfigurationContext.TerracottaCommandLineEnvironmentKeys.JCMD + terracottaServer.getServerSymbolicName().getSymbolicName();
    TerracottaCommandLineEnvironment tcEnv = tsaConfigurationContext.getTerracottaCommandLineEnvironment(whatFor);
    return new Jcmd(executor, instanceId, terracottaServer, tcEnv);
  }

  public Tsa create(TerracottaServer terracottaServer, String... startUpArgs) {
    return create(terracottaServer, Collections.emptyMap(), startUpArgs);
  }

  public Tsa create(TerracottaServer terracottaServer, Map<String, String> envOverrides, String... startUpArgs) {
    TerracottaServerState terracottaServerState = getState(terracottaServer);
    switch (terracottaServerState) {
      case STARTING:
      case STARTED_AS_ACTIVE:
      case STARTED_AS_PASSIVE:
      case STARTED_IN_DIAGNOSTIC_MODE:
        return this;
      case STOPPED:
        final AgentID agentID = executor.getAgentID(terracottaServer.getHostName());
        logger.info("Creating TSA: {} on: {}", instanceId, agentID);
        String whatFor = SERVER_START_PREFIX + terracottaServer.getServerSymbolicName().getSymbolicName();
        TerracottaCommandLineEnvironment cliEnv = tsaConfigurationContext.getTerracottaCommandLineEnvironment(whatFor);
        Duration inactivityKillerDelay = tsaConfigurationContext.getInactivityKillerDelay();
        IgniteRunnable tsaCreator = () -> AgentController.getInstance().createTsa(instanceId, terracottaServer, cliEnv, envOverrides, Arrays.asList(startUpArgs), inactivityKillerDelay);
        executor.execute(agentID, tsaCreator);
        return this;
    }
    throw new IllegalStateException("Cannot create: server " + terracottaServer.getServerSymbolicName() + " in state " + terracottaServerState);
  }

  public Tsa startAll(String... startUpArgs) {
    tsaConfigurationContext.getTopology().getServers().stream()
        .map(server -> CompletableFuture.runAsync(() -> start(server, startUpArgs)))
        .reduce(CompletableFuture::allOf).ifPresent(CompletableFuture::join);
    return this;
  }

  public DisruptionController disruptionController() {
    return disruptionController;
  }

  public Tsa start(TerracottaServer terracottaServer, String... startUpArgs) {
    return start(terracottaServer, Collections.emptyMap(), startUpArgs);
  }

  public Tsa start(TerracottaServer terracottaServer, Map<String, String> envOverrides, String... startUpArgs) {
    create(terracottaServer, envOverrides, startUpArgs);
    IgniteRunnable runnable = () -> AgentController.getInstance().waitForTsaInState(instanceId, terracottaServer, of(STARTED_AS_ACTIVE, STARTED_AS_PASSIVE, STARTED_IN_DIAGNOSTIC_MODE, START_SUSPENDED, STOPPED));
    final AgentID agentID = executor.getAgentID(terracottaServer.getHostName());
    executor.execute(agentID, runnable);
    logger.info("TSA: {} started on: {}", instanceId, agentID);
    return this;
  }

  public Tsa stopAll() {
    List<Exception> exceptions = new ArrayList<>();

    Topology topology = tsaConfigurationContext.getTopology();
    for (TerracottaServer terracottaServer : topology.getServers()) {
      try {
        stop(terracottaServer);
      } catch (Exception e) {
        exceptions.add(e);
      }
    }

    if (!exceptions.isEmpty()) {
      RuntimeException re = new RuntimeException("Error stopping all servers");
      exceptions.forEach(re::addSuppressed);
      throw re;
    }
    return this;
  }

  public Tsa stop(TerracottaServer terracottaServer) {
    TerracottaServerState terracottaServerState = getState(terracottaServer);
    if (terracottaServerState == STOPPED) {
      return this;
    }
    final AgentID agentID = executor.getAgentID(terracottaServer.getHostName());
    logger.info("Stopping TSA: {} on: {}", instanceId, agentID);
    executor.execute(agentID, () -> AgentController.getInstance().stopTsa(instanceId, terracottaServer));
    return this;
  }

  public Map<ServerSymbolicName, Integer> updateToProxiedPorts() {
    return disruptionController.updateTsaPortsWithProxy(tsaConfigurationContext.getTopology(), portAllocator);
  }

  public TerracottaServerState getState(TerracottaServer terracottaServer) {
    final AgentID agentID = executor.getAgentID(terracottaServer.getHostName());
    logger.debug("Getting state for TSA: {} on: {}", instanceId, agentID);
    return executor.execute(agentID, () -> AgentController.getInstance().getTsaState(instanceId, terracottaServer));
  }

  public Map<ServerSymbolicName, Integer> getProxyGroupPortsForServer(TerracottaServer terracottaServer) {
    final AgentID agentID = executor.getAgentID(terracottaServer.getHostName());
    logger.debug("Getting proxy group ports for TSA: {} on: {}", instanceId, agentID);
    return executor.execute(agentID, () -> AgentController.getInstance().getProxyGroupPortsForServer(instanceId, terracottaServer));
  }

  public Collection<TerracottaServer> getStarted() {
    Collection<TerracottaServer> allRunningServers = new ArrayList<>();
    allRunningServers.addAll(getActives());
    allRunningServers.addAll(getPassives());
    allRunningServers.addAll(getDiagnosticModeSevers());
    return allRunningServers;
  }

  public Collection<TerracottaServer> getStopped() {
    Collection<TerracottaServer> result = new ArrayList<>();
    for (TerracottaServer terracottaServer : tsaConfigurationContext.getTopology().getServers()) {
      if (getState(terracottaServer) == STOPPED) {
        result.add(terracottaServer);
      }
    }
    return result;
  }

  public Collection<TerracottaServer> getPassives() {
    Collection<TerracottaServer> result = new ArrayList<>();
    for (TerracottaServer terracottaServer : tsaConfigurationContext.getTopology().getServers()) {
      if (getState(terracottaServer) == STARTED_AS_PASSIVE) {
        result.add(terracottaServer);
      }
    }
    return result;
  }

  @SuppressWarnings("BusyWait")
  public Collection<TerracottaServer> waitForPassives(int count) throws InterruptedException {
    Collection<TerracottaServer> terracottaServers;
    while ((terracottaServers = getPassives()).size() < count) {
      Thread.sleep(500);
    }
    return terracottaServers;
  }

  public TerracottaServer getPassive() {
    Collection<TerracottaServer> servers = getPassives();
    switch (servers.size()) {
      case 0:
        return null;
      case 1:
        return servers.iterator().next();
      default:
        throw new IllegalStateException("There is more than one Passive Terracotta server, found " + servers.size());
    }
  }

  @SuppressWarnings("BusyWait")
  public TerracottaServer waitForPassive() throws InterruptedException {
    TerracottaServer terracottaServer;
    while ((terracottaServer = getPassive()) == null) {
      Thread.sleep(500);
    }
    return terracottaServer;
  }

  public Collection<TerracottaServer> getActives() {
    Collection<TerracottaServer> result = new ArrayList<>();
    for (TerracottaServer terracottaServer : tsaConfigurationContext.getTopology().getServers()) {
      if (getState(terracottaServer) == STARTED_AS_ACTIVE) {
        result.add(terracottaServer);
      }
    }
    return result;
  }

  public Collection<TerracottaServer> getServer(ServerSymbolicName symbolicName) {
    return tsaConfigurationContext.getTopology().getServers().stream()
        .filter(server -> server.getServerSymbolicName().equals(symbolicName))
        .collect(Collectors.toList());
  }

  public TerracottaServer getServer(int stripeIndex, int serverIndex) {
    return tsaConfigurationContext.getTopology().getServer(stripeIndex, serverIndex);
  }

  public Collection<Integer> getStripeIdOf(ServerSymbolicName symbolicName) {
    Collection<Integer> stripeIndices = new ArrayList<>();
    List<List<TerracottaServer>> stripes = tsaConfigurationContext.getTopology().getStripes();
    for (int i = 0; i < stripes.size(); i++) {
      List<TerracottaServer> stripe = stripes.get(i);
      if (stripe.stream().anyMatch(server -> server.getServerSymbolicName().equals(symbolicName))) {
        stripeIndices.add(i);
      }
    }
    return stripeIndices;
  }

  public TerracottaServer waitForActive() throws InterruptedException {
    TerracottaServer terracottaServer;
    while ((terracottaServer = getActive()) == null) {
      Thread.sleep(500);
    }
    return terracottaServer;
  }

  public TerracottaServer getActive() {
    Collection<TerracottaServer> servers = getActives();
    switch (servers.size()) {
      case 0:
        return null;
      case 1:
        return servers.iterator().next();
      default:
        throw new IllegalStateException("There is more than one Active Terracotta server, found " + servers.size());
    }
  }

  public Collection<TerracottaServer> getDiagnosticModeSevers() {
    Collection<TerracottaServer> result = new ArrayList<>();
    for (TerracottaServer terracottaServer : tsaConfigurationContext.getTopology().getServers()) {
      if (getState(terracottaServer) == STARTED_IN_DIAGNOSTIC_MODE) {
        result.add(terracottaServer);
      }
    }
    return result;
  }

  public TerracottaServer getDiagnosticModeServer() {
    Collection<TerracottaServer> servers = getDiagnosticModeSevers();
    switch (servers.size()) {
      case 0:
        return null;
      case 1:
        return servers.iterator().next();
      default:
        throw new IllegalStateException("There is more than one diagnostic mode server, found " + servers.size());
    }
  }

  public URI uri() {
    if (disruptionController == null) {
      throw new IllegalStateException("uri cannot be built from a client lambda - please call uri() from the test code instead");
    }
    Topology topology = tsaConfigurationContext.getTopology();
    Map<ServerSymbolicName, Integer> proxyTsaPorts = topology.isNetDisruptionEnabled() ?
        disruptionController.getProxyTsaPorts() : Collections.emptyMap();
    return topology.getDistribution().createDistributionController().tsaUri(topology.getServers(), proxyTsaPorts);
  }

  public RemoteFolder browse(TerracottaServer terracottaServer, String root) {
    final AgentID agentID = executor.getAgentID(terracottaServer.getHostName());
    final AgentExecutor agentExecutor = executor.forAgent(agentID);
    String path = agentExecutor.execute(() -> AgentController.getInstance().getTsaInstallPath(instanceId, terracottaServer));
    return new RemoteFolder(agentExecutor, path, root);
  }

  public RemoteFolder browseFromKitLocation(TerracottaServer terracottaServer, String relativePath) {
    final AgentID agentID = executor.getAgentID(terracottaServer.getHostName());
    final AgentExecutor agentExecutor = executor.forAgent(agentID);
    String kitLocation = agentExecutor.execute(() -> AgentController.getInstance().getTsaKitLocation(instanceId, terracottaServer));
    return new RemoteFolder(agentExecutor, kitLocation, relativePath);
  }

  public void uploadPlugin(File localPluginFile) {
    uploadPlugin(localPluginFile.toPath());
  }

  public void uploadPlugin(Path localPluginFile) {
    List<Exception> exceptions = new ArrayList<>();

    Topology topology = tsaConfigurationContext.getTopology();
    for (TerracottaServer server : topology.getServers()) {
      try {
        browseFromKitLocation(
            server,
            topology.getDistribution().createDistributionController().pluginJarsRootFolderName(topology.getDistribution())
        ).upload(localPluginFile);
      } catch (IOException ioe) {
        exceptions.add(ioe);
      }
    }

    if (!exceptions.isEmpty()) {
      RuntimeException re = new RuntimeException("Error uploading TSA plugin");
      exceptions.forEach(re::addSuppressed);
      throw re;
    }
  }

  public void uploadDataDirectories(File localRootPath) {
    uploadDataDirectories(localRootPath.toPath());
  }

  public void uploadDataDirectories(Path localRootPath) {
    List<Exception> exceptions = new ArrayList<>();

    Topology topology = tsaConfigurationContext.getTopology();
    ConfigurationManager configurationManager = topology.getConfigurationManager();
    if (configurationManager instanceof TcConfigManager) {
      TcConfigManager tcConfigProvider = (TcConfigManager) configurationManager;
      List<TcConfig> tcConfigs = tcConfigProvider.getTcConfigs();
      for (TcConfig tcConfig : tcConfigs) {
        Collection<String> dataDirectories = tcConfig.getDataDirectories().values();
        List<TerracottaServer> servers = tcConfig.getServers();
        for (String directory : dataDirectories) {
          for (TerracottaServer server : servers) {
            try {
              Path localFile = localRootPath.resolve(server.getServerSymbolicName().getSymbolicName()).resolve(directory);
              browse(server, directory).upload(localFile);
            } catch (IOException ioe) {
              exceptions.add(ioe);
            }
          }
        }
      }
    }

    if (!exceptions.isEmpty()) {
      RuntimeException re = new RuntimeException("Error uploading TSA data directories");
      exceptions.forEach(re::addSuppressed);
      throw re;
    }
  }

  public void downloadDataDirectories(File localRootPath) {
    downloadDataDirectories(localRootPath.toPath());
  }

  public void downloadDataDirectories(Path localRootPath) {
    List<Exception> exceptions = new ArrayList<>();

    Topology topology = tsaConfigurationContext.getTopology();
    ConfigurationManager configurationManager = topology.getConfigurationManager();
    if (configurationManager instanceof TcConfigManager) {
      TcConfigManager tcConfigProvider = (TcConfigManager) configurationManager;
      List<TcConfig> tcConfigs = tcConfigProvider.getTcConfigs();
      for (TcConfig tcConfig : tcConfigs) {
        Map<String, String> dataDirectories = tcConfig.getDataDirectories();
        List<TerracottaServer> servers = tcConfig.getServers();
        for (TerracottaServer server : servers) {
          for (Map.Entry<String, String> entry : dataDirectories.entrySet()) {
            String directory = entry.getValue();
            try {
              browse(server, directory).downloadTo(localRootPath.resolve(server.getServerSymbolicName().getSymbolicName()).resolve(directory));
            } catch (IOException ioe) {
              exceptions.add(ioe);
            }
          }
        }
      }
    }

    if (!exceptions.isEmpty()) {
      RuntimeException re = new RuntimeException("Error downloading TSA data directories");
      exceptions.forEach(re::addSuppressed);
      throw re;
    }
  }

  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;

    stopAll();
    if (!SKIP_UNINSTALL.getBooleanValue()) {
      uninstallAll();
    }

    if (tsaConfigurationContext.getTopology().isNetDisruptionEnabled()) {
      try {
        disruptionController.close();
      } catch (Exception e) {
        logger.error("Error when trying to close traffic controller : {}", e.getMessage());
      }
    }
  }

}
