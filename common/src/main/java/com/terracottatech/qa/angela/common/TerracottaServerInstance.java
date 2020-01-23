package com.terracottatech.qa.angela.common;

import com.terracottatech.qa.angela.common.distribution.Distribution;
import com.terracottatech.qa.angela.common.distribution.DistributionController;
import com.terracottatech.qa.angela.common.net.DisruptionProvider;
import com.terracottatech.qa.angela.common.net.DisruptionProviderFactory;
import com.terracottatech.qa.angela.common.net.Disruptor;
import com.terracottatech.qa.angela.common.tcconfig.License;
import com.terracottatech.qa.angela.common.tcconfig.SecurityRootDirectory;
import com.terracottatech.qa.angela.common.tcconfig.ServerSymbolicName;
import com.terracottatech.qa.angela.common.tcconfig.TerracottaServer;
import com.terracottatech.qa.angela.common.topology.Topology;
import org.zeroturnaround.process.Processes;

import java.io.Closeable;
import java.io.File;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

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
  private final DistributionController distributionController;
  private final File installLocation;
  private final Distribution distribution;
  private final File licenseFileLocation;
  private volatile TerracottaServerInstanceProcess terracottaServerInstanceProcess;
  private final boolean netDisruptionEnabled;
  private final Topology topology;

  public TerracottaServerInstance(TerracottaServer terracottaServer,
                                  File installLocation,
                                  License license,
                                  Distribution distribution,
                                  Topology topology) {
    this.terracottaServer = terracottaServer;
    this.distributionController = distribution.createDistributionController();
    this.installLocation = installLocation;
    this.distribution = distribution;
    this.licenseFileLocation = license == null ? null : new File(installLocation, license.getFilename());
    this.netDisruptionEnabled = topology.isNetDisruptionEnabled();
    this.topology = topology;
    constructLinks();
  }

  private void constructLinks() {
    if (netDisruptionEnabled) {
      topology.getConfigurationManager().createDisruptionLinks(terracottaServer, DISRUPTION_PROVIDER, disruptionLinks, proxiedPorts);
    }
  }

  public Map<ServerSymbolicName, Integer> getProxiedPorts() {
    return proxiedPorts;
  }

  public Distribution getDistribution() {
    return distribution;
  }

  public void create(TerracottaCommandLineEnvironment env, List<String> startUpArgs) {
    this.terracottaServerInstanceProcess = this.distributionController.createTsa(terracottaServer, installLocation, topology, proxiedPorts, env, startUpArgs);
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

  public void stop(TerracottaCommandLineEnvironment tcEnv) {
    this.distributionController.stopTsa(terracottaServer.getServerSymbolicName(), installLocation, terracottaServerInstanceProcess, tcEnv);
  }

  @Override
  public void close() {
    removeDisruptionLinks();
  }

  public void configure(String clusterName, String licensePath, Topology topology, Map<ServerSymbolicName, Integer> proxyTsaPorts, SecurityRootDirectory securityRootDirectory, TerracottaCommandLineEnvironment env, boolean verbose) {
    this.distributionController.configure(clusterName, installLocation, licensePath, topology, proxyTsaPorts, securityRootDirectory, env, verbose);
  }

  public ClusterToolExecutionResult clusterTool(TerracottaCommandLineEnvironment env, String... arguments) {
    return distributionController.invokeClusterTool(installLocation, env, arguments);
  }

  public ConfigToolExecutionResult configTool(TerracottaCommandLineEnvironment env, String... arguments) {
    return distributionController.invokeConfigTool(installLocation, env, arguments);
  }

  public ToolExecutionResult jcmd(TerracottaCommandLineEnvironment env, String... arguments) {
    return distributionController.invokeJcmd(terracottaServerInstanceProcess, env, arguments);
  }

  public void waitForState(Predicate<TerracottaServerState> condition) {
    while (this.terracottaServerInstanceProcess.isAlive() && !condition.test(getTerracottaServerState())) {
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      }
    }
    if (!this.terracottaServerInstanceProcess.isAlive()) {
      throw new RuntimeException("TC server died while waiting on state-change condition");
    }
  }

  public TerracottaServerState getTerracottaServerState() {
    if (this.terracottaServerInstanceProcess == null) {
      return TerracottaServerState.STOPPED;
    } else {
      return this.terracottaServerInstanceProcess.getState();
    }
  }

  public File getInstallLocation() {
    return installLocation;
  }

  public File getLicenseFileLocation() {
    return licenseFileLocation;
  }

  public static class TerracottaServerInstanceProcess {
    private final AtomicReference<TerracottaServerState> state;
    private final Number wrapperPid;
    private final Number javaPid;

    public TerracottaServerInstanceProcess(AtomicReference<TerracottaServerState> state, Number wrapperPid, Number javaPid) {
      Objects.requireNonNull(wrapperPid, "wrapperPid cannot be null");
      if (wrapperPid.intValue() < 1 || (javaPid != null && javaPid.intValue() < 1)) {
        throw new IllegalArgumentException("Pid cannot be < 1");
      }
      this.wrapperPid = wrapperPid;
      this.javaPid = javaPid;
      this.state = state;
    }

    public TerracottaServerState getState() {
      return state.get();
    }

    public Set<Number> getPids() {
      Set<Number> pids = new HashSet<>();
      pids.add(wrapperPid);
      if (javaPid != null) {
        pids.add(javaPid);
      }
      return Collections.unmodifiableSet(pids);
    }

    public Number getJavaPid() {
      return javaPid;
    }

    public boolean isAlive() {
      try {
        // if at least one PID is alive, the process is considered alive
        return (wrapperPid != null && Processes.newPidProcess(wrapperPid.intValue()).isAlive()) ||
            (javaPid != null && Processes.newPidProcess(javaPid.intValue()).isAlive());
      } catch (Exception e) {
        throw new RuntimeException("Error checking liveness of a process instance with PIDs " + wrapperPid + " and " + javaPid, e);
      }
    }
  }

  private void removeDisruptionLinks() {
    if (netDisruptionEnabled) {
      disruptionLinks.values().forEach(DISRUPTION_PROVIDER::removeLink);
    }
  }
}
