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
package org.terracotta.angela.common;

import org.terracotta.angela.common.distribution.Distribution;
import org.terracotta.angela.common.distribution.DistributionController;
import org.terracotta.angela.common.net.DisruptionProvider;
import org.terracotta.angela.common.net.DisruptionProviderFactory;
import org.terracotta.angela.common.net.Disruptor;
import org.terracotta.angela.common.net.PortAllocator;
import org.terracotta.angela.common.tcconfig.License;
import org.terracotta.angela.common.tcconfig.ServerSymbolicName;
import org.terracotta.angela.common.tcconfig.TerracottaServer;
import org.terracotta.angela.common.topology.Topology;
import org.terracotta.angela.common.util.Jcmd;

import java.io.Closeable;
import java.io.File;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Terracotta server instance
 *
 * @author Aurelien Broszniowski
 */
public class TerracottaServerInstance implements Closeable {
  private static final DisruptionProvider DISRUPTION_PROVIDER = DisruptionProviderFactory.getDefault();
  private final Map<ServerSymbolicName, Disruptor> disruptionLinks = new ConcurrentHashMap<>();
  private final Map<ServerSymbolicName, Integer> proxiedPorts = new HashMap<>();
  private final TerracottaServer terracottaServer;
  private final File kitDir;
  private final DistributionController distributionController;
  private final File workingDir;
  private final Distribution distribution;
  private final PortAllocator portAllocator;
  private final String licenseFilename;
  private volatile TerracottaServerHandle serverInstance;
  private final boolean netDisruptionEnabled;
  private final Topology topology;

  public TerracottaServerInstance(TerracottaServer terracottaServer, File kitDir, File workingDir,
                                  License license, Distribution distribution, Topology topology,
                                  PortAllocator portAllocator) {
    this.terracottaServer = terracottaServer;
    this.kitDir = kitDir;
    this.distributionController = distribution.createDistributionController();
    this.workingDir = workingDir;
    this.distribution = distribution;
    this.portAllocator = portAllocator;
    this.licenseFilename = license == null ? null : license.getFilename();
    this.netDisruptionEnabled = topology.isNetDisruptionEnabled();
    this.topology = topology;
    constructLinks();
  }

  private void constructLinks() {
    if (netDisruptionEnabled) {
      topology.getConfigurationManager()
          .createDisruptionLinks(terracottaServer, DISRUPTION_PROVIDER, disruptionLinks, proxiedPorts, portAllocator);
    }
  }

  public Map<ServerSymbolicName, Integer> getProxiedPorts() {
    return proxiedPorts;
  }

  public Distribution getDistribution() {
    return distribution;
  }

  public void create(TerracottaCommandLineEnvironment env, Map<String, String> envOverrides, List<String> startUpArgs, Duration inactivityKillerDelay) {
    setServerHandle(this.distributionController.createTsa(terracottaServer, kitDir, workingDir, topology, proxiedPorts, env, envOverrides, startUpArgs, inactivityKillerDelay));
  }

  private synchronized TerracottaServerHandle getServerHandle() {
    return this.serverInstance;
  }

  private synchronized void setServerHandle(TerracottaServerHandle handle) {
    this.serverInstance = handle;
  }

  public void disrupt(Collection<TerracottaServer> targets) {
    if (!netDisruptionEnabled) {
      throw new IllegalArgumentException("Topology not enabled for network disruption");
    }
    for (TerracottaServer server : targets) {
      disruptionLinks.get(server.getServerSymbolicName()).disrupt();
    }
  }

  public void undisrupt(Collection<TerracottaServer> targets) {
    if (!netDisruptionEnabled) {
      throw new IllegalArgumentException("Topology not enabled for network disruption");
    }
    for (TerracottaServer target : targets) {
      disruptionLinks.get(target.getServerSymbolicName()).undisrupt();
    }
  }

  public void stop() {
    getServerHandle().stop();
  }

  @Override
  public void close() {
    removeDisruptionLinks();
  }

  public ToolExecutionResult jcmd(TerracottaCommandLineEnvironment env, String... arguments) {
    return Jcmd.jcmd(getServerHandle().getJavaPid(), env, arguments);
  }

  public void waitForState(Set<TerracottaServerState> terracottaServerStates) {
    boolean isStateSame = true;
    TerracottaServerHandle handle = getServerHandle();
    while (isStateSame) {
      try {
        Thread.sleep(100);

        isStateSame = handle.isAlive();
        TerracottaServerState currentState = handle.getState();
        for (TerracottaServerState terracottaServerState : terracottaServerStates) {
          isStateSame &= (terracottaServerState != currentState);
        }
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      }
    }
    if (!handle.isAlive()) {
      StringBuilder states = new StringBuilder();
      for (TerracottaServerState terracottaServerState : terracottaServerStates) {
        states.append(terracottaServerState).append(" ");
      }
      throw new RuntimeException("The Terracotta server was in state " + handle.getState() +
                                 " and was expected to reach one of the states: " + states.toString()
                                 + "but died before reaching it.");
    }
  }

  public TerracottaServerState getTerracottaServerState() {
    TerracottaServerHandle handle = getServerHandle();
    if (handle == null) {
      return TerracottaServerState.STOPPED;
    } else {
      return handle.getState();
    }
  }

  public File getKitDir() {
    return kitDir;
  }

  public File getWorkingDir() {
    return workingDir;
  }

  public File getLicenseFileLocation() {
    // now workingDir == server folder
    File f = new File(workingDir, licenseFilename);
    if (f.exists()) {
      return f;
    }
    // check in temporary angela work dir (this one will work now)
    f = new File(workingDir.getParentFile(), licenseFilename);
    if (f.exists()) {
      return f;
    }
    return null;
  }

  private void removeDisruptionLinks() {
    if (netDisruptionEnabled) {
      disruptionLinks.values().forEach(DISRUPTION_PROVIDER::removeLink);
    }
  }
}
