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
import org.terracotta.angela.agent.com.AgentID;
import org.terracotta.angela.agent.com.Executor;
import org.terracotta.angela.agent.kit.LocalKitManager;
import org.terracotta.angela.client.config.WebMIsConfigurationContext;
import org.terracotta.angela.common.TerracottaCommandLineEnvironment;
import org.terracotta.angela.common.TerracottaManagementServerState;
import org.terracotta.angela.common.WebMIsServerState;
import org.terracotta.angela.common.distribution.Distribution;
import org.terracotta.angela.common.net.PortAllocator;
import org.terracotta.angela.common.tcconfig.License;
import org.terracotta.angela.common.topology.InstanceId;
import org.terracotta.angela.common.topology.WebMIsTopology;

import java.util.function.Supplier;

import static org.terracotta.angela.common.AngelaProperties.SKIP_UNINSTALL;
import static org.terracotta.angela.common.WebMIsServerState.STARTED;
import static org.terracotta.angela.common.WebMIsServerState.STOPPED;

/**
 * @author Aurelien Broszniowski
 */

public class WebMIs implements AutoCloseable {

  private final static Logger logger = LoggerFactory.getLogger(WebMIs.class);
  private final Executor executor;
  private final PortAllocator portAllocator;
  private final InstanceId instanceId;
  private final WebMIsConfigurationContext webMIsConfigurationContext;
  private final transient LocalKitManager localKitManager;

  private boolean closed = false;

  WebMIs(Executor executor, PortAllocator portAllocator, InstanceId instanceId, WebMIsConfigurationContext webMIsConfigurationContext) {
    this.executor = executor;
    this.portAllocator = portAllocator;
    this.instanceId = instanceId;
    this.webMIsConfigurationContext = webMIsConfigurationContext;
    this.localKitManager = new LocalKitManager(portAllocator, webMIsConfigurationContext.getTopology().getDistribution());
    installAll();
  }

  void installAll() {
    WebMIsTopology topology = webMIsConfigurationContext.getTopology();

    WebMIsServerState webMIsServerState = getWebMIsState();
    if (webMIsServerState != WebMIsServerState.NOT_INSTALLED) {
      throw new IllegalStateException("Cannot install: server on [" + webMIsConfigurationContext.getTopology().getWebMIsServer().getHostName() + "] in state " + webMIsServerState);
    }
    Distribution distribution = localKitManager.getDistribution();
    License license = webMIsConfigurationContext.getTopology().getWebMIsLicense();

    localKitManager.setupLocalWebMInstall(license, webMIsConfigurationContext.getTerracottaCommandLineEnvironment());
    final String kitInstallationName = localKitManager.getKitInstallationName();
    final AgentID agentID = executor.getAgentID(webMIsConfigurationContext.getTopology().getWebMIsServer().getHostName());

    logger.info("Installing WebM IS: {} on: {}", instanceId, agentID);
    TerracottaCommandLineEnvironment tcEnv = webMIsConfigurationContext.getTerracottaCommandLineEnvironment();
    final IgniteCallable<Boolean> installClosure = () -> AgentController.getInstance().installWebMIs(instanceId, webMIsConfigurationContext.getTopology().getWebMIsServer().getHostName(), distribution, license, kitInstallationName, tcEnv);
    boolean isRemoteInstallationSuccessful = executor.execute(agentID, installClosure);
    if (!isRemoteInstallationSuccessful) {
      try {
        logger.debug("Uploading: {} on: {}", distribution, agentID);
        executor.uploadKit(agentID, instanceId, distribution, kitInstallationName, localKitManager.getKitInstallationPath());
        executor.execute(agentID, installClosure);
      } catch (Exception e) {
        throw new RuntimeException("Cannot upload kit to " + webMIsConfigurationContext.getTopology().getWebMIsServer().getHostName(), e);
      }
    }
  }

  public WebMIsServerState getWebMIsState() {
    final AgentID agentID = executor.getAgentID(webMIsConfigurationContext.getTopology().getWebMIsServer().getHostName());
    logger.debug("Get state of TMS: {} on: {}", instanceId, agentID);
    return executor.execute(agentID, () -> AgentController.getInstance().getWebMIsState(instanceId));
  }

  public WebMIs start() {
    WebMIsServerState serverState = getWebMIsState();
    switch (serverState) {
      case STARTING:
      case STARTED:
        return this;
      case STOPPED:
        final AgentID agentID = executor.getAgentID(webMIsConfigurationContext.getTopology().getWebMIsServer().getHostName());
        logger.info("Creating WebM IS: {} on: {}", instanceId, agentID);
        executor.execute(agentID, () -> AgentController.getInstance().startWebMIs(instanceId));
        return this;
    }
    throw new IllegalStateException("Cannot create: WebMIs server on " + webMIsConfigurationContext.getTopology().getWebMIsServer().getHostName() + " in state " + serverState);

  }

  public void stop() {
    WebMIsServerState serverState = getWebMIsState();
    if (serverState == STOPPED) {
      return;
    }
    if (serverState != STARTED) {
      throw new IllegalStateException("Cannot stop: webMethods IS server , already in state " + serverState);
    }
    final AgentID agentID = executor.getAgentID(webMIsConfigurationContext.getTopology().getWebMIsServer().getHostName());
    logger.info("Creating WebM IS: {} on: {}", instanceId, agentID);
    executor.execute(agentID, () -> AgentController.getInstance().stopWebMIs(instanceId));
    ensureStopped(this::getWebMIsState);
  }

  public void uninstallAll() {
    final String hostName = webMIsConfigurationContext.getTopology().getWebMIsServer().getHostName();
    WebMIsServerState serverState = getWebMIsState();
    if (serverState == null) {
      return;
    }
    if (serverState != STOPPED) {
      throw new IllegalStateException("Cannot stop: webMethods IS server , already in state " + serverState);
    }
    final AgentID agentID = executor.getAgentID(webMIsConfigurationContext.getTopology().getWebMIsServer().getHostName());
    logger.info("Uninstalling WebMthods IS: {} from: {}", instanceId, agentID);
    final String kitInstallationName = localKitManager.getKitInstallationName();
    Distribution distribution = localKitManager.getDistribution();
    License license = webMIsConfigurationContext.getTopology().getWebMIsLicense();
    TerracottaCommandLineEnvironment tcEnv = webMIsConfigurationContext.getTerracottaCommandLineEnvironment();

    executor.execute(agentID, () -> AgentController.getInstance().uninstallWebMIs(instanceId, hostName, distribution, license, kitInstallationName, tcEnv));
  }

  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;

    stop();
    if (!SKIP_UNINSTALL.getBooleanValue()) {
      uninstallAll();
    }
  }

  @SuppressWarnings("BusyWait")
  private void ensureStopped(Supplier<WebMIsServerState> s) {
    try {
      while (s.get() != STOPPED) {
        Thread.sleep(200);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    }
  }

}
