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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terracotta.angela.agent.AgentController;
import org.terracotta.angela.agent.com.AgentExecutor;
import org.terracotta.angela.agent.com.AgentID;
import org.terracotta.angela.agent.com.Executor;
import org.terracotta.angela.agent.kit.LocalKitManager;
import org.terracotta.angela.client.config.TmsConfigurationContext;
import org.terracotta.angela.client.filesystem.RemoteFolder;
import org.terracotta.angela.common.TerracottaCommandLineEnvironment;
import org.terracotta.angela.common.TerracottaManagementServerState;
import org.terracotta.angela.common.distribution.Distribution;
import org.terracotta.angela.common.net.PortAllocator;
import org.terracotta.angela.common.tcconfig.License;
import org.terracotta.angela.common.tms.security.config.TmsClientSecurityConfig;
import org.terracotta.angela.common.tms.security.config.TmsServerSecurityConfig;
import org.terracotta.angela.common.topology.InstanceId;
import org.terracotta.angela.common.util.HostPort;

import java.util.Collections;
import java.util.Map;
import java.util.function.Supplier;

import static org.terracotta.angela.common.AngelaProperties.KIT_INSTALLATION_DIR;
import static org.terracotta.angela.common.AngelaProperties.KIT_INSTALLATION_PATH;
import static org.terracotta.angela.common.AngelaProperties.OFFLINE;
import static org.terracotta.angela.common.AngelaProperties.SKIP_UNINSTALL;
import static org.terracotta.angela.common.AngelaProperties.getEitherOf;

public class Tms implements AutoCloseable {

  private final static Logger logger = LoggerFactory.getLogger(Tsa.class);

  private final transient TmsConfigurationContext tmsConfigurationContext;
  private boolean closed = false;
  private final transient Executor executor;
  private final InstanceId instanceId;
  private final transient LocalKitManager localKitManager;
  private volatile int port;

  @Deprecated
  private static final String NONE = "none";
  @Deprecated
  private static final String BROWSER_SECURITY = "browser-security";
  @Deprecated
  private static final String CLUSTER_SECURITY = "cluster-security";
  @Deprecated
  public static final String FULL = "full";

  Tms(Executor executor, PortAllocator portAllocator, InstanceId instanceId, TmsConfigurationContext tmsConfigurationContext) {
    this.tmsConfigurationContext = tmsConfigurationContext;
    this.instanceId = instanceId;
    this.executor = executor;
    this.localKitManager = new LocalKitManager(portAllocator, tmsConfigurationContext.getDistribution());
    install();
  }

  public TmsConfigurationContext getTmsConfigurationContext() {
    return tmsConfigurationContext;
  }

  public String url() {
    boolean isHttps = false;
    TmsServerSecurityConfig tmsServerSecurityConfig = tmsConfigurationContext.getSecurityConfig();
    if (tmsServerSecurityConfig != null) {
      isHttps = ("true".equals(tmsServerSecurityConfig.getTmsSecurityHttpsEnabled())
          || FULL.equals(tmsServerSecurityConfig.getDeprecatedSecurityLevel())
          || BROWSER_SECURITY.equals(tmsServerSecurityConfig.getDeprecatedSecurityLevel())
      );
    }
    if (port == 0) {
      throw new IllegalStateException("TMS not started");
    }
    return (isHttps ? "https://" : "http://") + new HostPort(tmsConfigurationContext.getHostName(), port).getHostPort();
  }

  public TmsHttpClient httpClient() {
    return httpClient(null);
  }

  public TmsHttpClient httpClient(TmsClientSecurityConfig tmsClientSecurityConfig) {
    return new TmsHttpClient(url(), tmsClientSecurityConfig);
  }

  public RemoteFolder browse(String root) {
    final AgentID agentID = executor.getAgentID(tmsConfigurationContext.getHostName());
    final AgentExecutor agentExecutor = executor.forAgent(agentID);
    logger.debug("Browse TMS: {} on: {}", instanceId, agentID);
    String path = agentExecutor.execute(() -> AgentController.getInstance().getTmsInstallationPath(instanceId));
    return new RemoteFolder(agentExecutor, path, root);
  }

  public TerracottaManagementServerState getTmsState() {
    final AgentID agentID = executor.getAgentID(tmsConfigurationContext.getHostName());
    logger.debug("Get state of TMS: {} on: {}", instanceId, agentID);
    return executor.execute(agentID, () -> AgentController.getInstance().getTmsState(instanceId));
  }

  public Tms start() {
    return start(Collections.emptyMap());
  }

  public Tms start(Map<String, String> envOverrides) {
    final AgentID agentID = executor.getAgentID(tmsConfigurationContext.getHostName());
    logger.info("Starting TMS: {} on: {}", instanceId, agentID);
    port = executor.execute(agentID, () -> AgentController.getInstance().startTms(instanceId, envOverrides));
    logger.info("TMS started on port: {}", port);
    return this;
  }

  public int getPort() {
    return port;
  }

  public void stop() {
    stopTms();
  }

  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;

    try {
      stop();
    } catch (Exception e) {
      e.printStackTrace();
      // ignore, not installed
    }
    if (!SKIP_UNINSTALL.getBooleanValue()) {
      uninstall();
    }
  }

  private void uninstall() {
    String tmsHostname = tmsConfigurationContext.getHostName();
    TerracottaManagementServerState terracottaServerState = getTmsState();
    if (terracottaServerState == null) {
      return;
    }
    if (terracottaServerState != TerracottaManagementServerState.STOPPED) {
      throw new IllegalStateException("Cannot uninstall: server " + tmsHostname + " already in state " + terracottaServerState);
    }
    final AgentID agentID = executor.getAgentID(tmsConfigurationContext.getHostName());
    logger.info("Uninstalling TMS: {} from: {}", instanceId, agentID);
    String kitInstallationPath = getEitherOf(KIT_INSTALLATION_DIR, KIT_INSTALLATION_PATH);
    final Distribution distribution = tmsConfigurationContext.getDistribution();
    final TmsServerSecurityConfig securityConfig = tmsConfigurationContext.getSecurityConfig();
    final String kitInstallationName = localKitManager.getKitInstallationName();
    executor.execute(agentID, () -> AgentController.getInstance().uninstallTms(instanceId, distribution, securityConfig, kitInstallationName, tmsHostname, kitInstallationPath));
  }

  private void install() {
    final String tmsHostname = tmsConfigurationContext.getHostName();
    License license = tmsConfigurationContext.getLicense();
    Distribution distribution = tmsConfigurationContext.getDistribution();
    TmsServerSecurityConfig tmsServerSecurityConfig = tmsConfigurationContext.getSecurityConfig();
    TerracottaCommandLineEnvironment tcEnv = tmsConfigurationContext.getTerracottaCommandLineEnvironment();
    final AgentID agentID = executor.getAgentID(tmsHostname);

    logger.info("Installing TMS: {} on: {}", instanceId, agentID);

    String kitInstallationPath = getEitherOf(KIT_INSTALLATION_DIR, KIT_INSTALLATION_PATH);
    localKitManager.setupLocalInstall(license, kitInstallationPath, OFFLINE.getBooleanValue());
    final String kitInstallationName = localKitManager.getKitInstallationName();

    IgniteCallable<Boolean> callable = () -> AgentController.getInstance().installTms(instanceId, tmsHostname, distribution, license, tmsServerSecurityConfig, kitInstallationName, tcEnv, tmsHostname, kitInstallationPath);
    boolean isRemoteInstallationSuccessful = executor.execute(agentID, callable);

    if (!isRemoteInstallationSuccessful) {
      try {
        executor.uploadKit(agentID, instanceId, distribution, kitInstallationName, localKitManager.getKitInstallationPath());
        executor.execute(agentID, callable);
      } catch (Exception e) {
        throw new RuntimeException("Cannot upload kit to " + tmsHostname, e);
      }
    }

  }

  private void stopTms() {
    TerracottaManagementServerState terracottaManagementServerState = getTmsState();
    if (terracottaManagementServerState == TerracottaManagementServerState.STOPPED) {
      return;
    }
    if (terracottaManagementServerState != TerracottaManagementServerState.STARTED) {
      throw new IllegalStateException("Cannot stop: TMS server , already in state " + terracottaManagementServerState);
    }

    final AgentID agentID = executor.getAgentID(tmsConfigurationContext.getHostName());
    logger.info("Stopping TMS: {} on: {}", instanceId, agentID);
    executor.execute(agentID, () -> AgentController.getInstance().stopTms(instanceId));
    ensureStopped(this::getTmsState);
  }

  // Ignite has a little cache or delay and when reading with getState() just after the process is stopped and
  // state put in the AtomicRed, Ignite could still see STARTED. It was taking a little while for Ignite to see
  // the STOPPED state through a remote call.
  @SuppressWarnings("BusyWait")
  private void ensureStopped(Supplier<TerracottaManagementServerState> s) {
    try {
      while (s.get() != TerracottaManagementServerState.STOPPED) {
        Thread.sleep(200);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    }
  }
}
