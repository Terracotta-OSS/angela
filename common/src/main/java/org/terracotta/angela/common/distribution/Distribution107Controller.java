/*
 * The contents of this file are subject to the Terracotta Public License Version
 * 2.0 (the "License"); You may not use this file except in compliance with the
 * License. You may obtain a copy of the License at
 *
 * http://terracotta.org/legal/terracotta-public-license.
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License for
 * the specific language governing rights and limitations under the License.
 *
 * The Covered Software is Angela.
 *
 * The Initial Developer of the Covered Software is
 * Terracotta, Inc., a Software AG company
 */

package org.terracotta.angela.common.distribution;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terracotta.angela.common.ClusterToolExecutionResult;
import org.terracotta.angela.common.ConfigToolExecutionResult;
import org.terracotta.angela.common.TerracottaCommandLineEnvironment;
import org.terracotta.angela.common.TerracottaManagementServerInstance.TerracottaManagementServerInstanceProcess;
import org.terracotta.angela.common.TerracottaManagementServerState;
import org.terracotta.angela.common.TerracottaServerInstance.TerracottaServerInstanceProcess;
import org.terracotta.angela.common.TerracottaServerState;
import org.terracotta.angela.common.tcconfig.SecurityRootDirectory;
import org.terracotta.angela.common.tcconfig.ServerSymbolicName;
import org.terracotta.angela.common.tcconfig.TerracottaServer;
import org.terracotta.angela.common.topology.PackageType;
import org.terracotta.angela.common.topology.Topology;
import org.terracotta.angela.common.util.ExternalLoggers;
import org.terracotta.angela.common.util.HostPort;
import org.terracotta.angela.common.util.OS;
import org.terracotta.angela.common.util.ProcessUtil;
import org.terracotta.angela.common.util.TriggeringOutputStream;
import org.zeroturnaround.exec.ProcessExecutor;
import org.zeroturnaround.exec.ProcessResult;
import org.zeroturnaround.exec.stream.slf4j.Slf4jStream;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static java.io.File.separator;
import static java.lang.Integer.parseInt;
import static java.util.regex.Pattern.compile;
import static org.terracotta.angela.common.AngelaProperties.TMS_FULL_LOGGING;
import static org.terracotta.angela.common.AngelaProperties.TSA_FULL_LOGGING;

public class Distribution107Controller extends DistributionController {
  private final static Logger LOGGER = LoggerFactory.getLogger(Distribution107Controller.class);
  private final boolean tsaFullLogging = Boolean.parseBoolean(TSA_FULL_LOGGING.getValue());
  private final boolean tmsFullLogging = Boolean.parseBoolean(TMS_FULL_LOGGING.getValue());

  public Distribution107Controller(Distribution distribution) {
    super(distribution);
  }

  @Override
  public TerracottaServerInstanceProcess createTsa(TerracottaServer terracottaServer, File installLocation,
                                                   Topology topology, Map<ServerSymbolicName, Integer> proxiedPorts,
                                                   TerracottaCommandLineEnvironment tcEnv, List<String> startUpArgs) {
    Map<String, String> env = buildEnv(tcEnv);
    AtomicReference<TerracottaServerState> stateRef = new AtomicReference<>(TerracottaServerState.STOPPED);
    AtomicInteger javaPid = new AtomicInteger(-1);

    TriggeringOutputStream serverLogOutputStream = TriggeringOutputStream
        .triggerOn(
            compile("^.*\\QStarted the server in diagnostic mode\\E.*$"),
            mr -> stateRef.set(TerracottaServerState.STARTED_IN_DIAGNOSTIC_MODE))
        .andTriggerOn(
            compile("^.*\\QTerracotta Server instance has started up as ACTIVE\\E.*$"),
            mr -> stateRef.set(TerracottaServerState.STARTED_AS_ACTIVE))
        .andTriggerOn(
            compile("^.*\\QMoved to State[ PASSIVE-STANDBY ]\\E.*$"),
            mr -> stateRef.set(TerracottaServerState.STARTED_AS_PASSIVE))
        .andTriggerOn(
            compile("^.*\\QL2 exiting\\E.*$"),
            mr -> stateRef.set(TerracottaServerState.STOPPED))
        .andTriggerOn(
            compile("^.*PID is (\\d+).*$"),
            mr -> {
              javaPid.set(parseInt(mr.group(1)));
              stateRef.compareAndSet(TerracottaServerState.STOPPED, TerracottaServerState.STARTING);
            })
        .andTriggerOn(
            tsaFullLogging ? compile("^.*$") : compile("^.*(WARN|ERROR).*$"),
            mr -> ExternalLoggers.tsaLogger.info("[{}] {}", terracottaServer.getServerSymbolicName().getSymbolicName(), mr.group()));

    WatchedProcess<TerracottaServerState> watchedProcess = new WatchedProcess<>(
        new ProcessExecutor()
            .command(createTsaCommand(terracottaServer, installLocation, startUpArgs))
            .directory(installLocation)
            .environment(env)
            .redirectError(System.err)
            .redirectOutput(serverLogOutputStream),
        stateRef,
        TerracottaServerState.STOPPED);

    while (javaPid.get() == -1 && watchedProcess.isAlive()) {
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      }
    }

    if (!watchedProcess.isAlive()) {
      throw new RuntimeException("Terracotta server process died in its infancy : " + terracottaServer.getServerSymbolicName());
    }
    return new TerracottaServerInstanceProcess(stateRef, watchedProcess.getPid(), javaPid);
  }

  @Override
  public TerracottaManagementServerInstanceProcess startTms(File installLocation, TerracottaCommandLineEnvironment tcEnv) {
    Map<String, String> env = buildEnv(tcEnv);

    AtomicReference<TerracottaManagementServerState> stateRef = new AtomicReference<>(TerracottaManagementServerState.STOPPED);
    AtomicInteger javaPid = new AtomicInteger(-1);

    TriggeringOutputStream outputStream = TriggeringOutputStream
        .triggerOn(
            compile("^.*\\Qstarted on port\\E.*$"),
            mr -> stateRef.set(TerracottaManagementServerState.STARTED))
        .andTriggerOn(
            compile("^.*\\QStarting TmsApplication\\E.*with PID (\\d+).*$"),
            mr -> javaPid.set(parseInt(mr.group(1))))
        .andTriggerOn(
            tmsFullLogging ? compile("^.*$") : compile("^.*(WARN|ERROR).*$"),
            mr -> ExternalLoggers.tmsLogger.info(mr.group()));

    WatchedProcess<TerracottaManagementServerState> watchedProcess = new WatchedProcess<>(new ProcessExecutor()
        .command(startTmsCommand(installLocation))
        .directory(installLocation)
        .environment(env)
        .redirectError(System.err)
        .redirectOutput(outputStream), stateRef, TerracottaManagementServerState.STOPPED);

    while ((javaPid.get() == -1 || stateRef.get() == TerracottaManagementServerState.STOPPED) && watchedProcess.isAlive()) {
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      }
    }
    if (!watchedProcess.isAlive()) {
      throw new RuntimeException("TMS process died before reaching STARTED state");
    }

    int wrapperPid = watchedProcess.getPid();
    int javaProcessPid = javaPid.get();
    return new TerracottaManagementServerInstanceProcess(stateRef, wrapperPid, javaProcessPid);
  }

  @Override
  public void stopTms(File installLocation, TerracottaManagementServerInstanceProcess terracottaServerInstanceProcess, TerracottaCommandLineEnvironment tcEnv) {
    LOGGER.debug("Destroying TMS process");
    for (Number pid : terracottaServerInstanceProcess.getPids()) {
      try {
        ProcessUtil.destroyGracefullyOrForcefullyAndWait(pid.intValue());
      } catch (Exception e) {
        LOGGER.error("Could not destroy TMS process {}", pid, e);
      }
    }
  }

  @Override
  public void configure(String clusterName, File location, String licensePath, Topology topology, Map<ServerSymbolicName,
      Integer> proxyTsaPorts, SecurityRootDirectory srd, TerracottaCommandLineEnvironment tcEnv, boolean verbose) {
    TerracottaServer server = topology.getServers().get(0);
    List<String> args = new ArrayList<>(Arrays.asList("activate", "-n", clusterName, "-s", server.getHostPort()));
    if (licensePath != null) {
      args.add("-l");
      args.add(licensePath);
    }
    invokeConfigTool(location, tcEnv, args.toArray(new String[0]));
  }

  @Override
  public ClusterToolExecutionResult invokeClusterTool(File installLocation, TerracottaCommandLineEnvironment env, String... arguments) {
    throw new UnsupportedOperationException("Dynamic config doesn't use cluster-tool");
  }

  @Override
  public ConfigToolExecutionResult invokeConfigTool(File installLocation, TerracottaCommandLineEnvironment tcEnv, String... arguments) {
    List<String> command = new ArrayList<>();
    command.add(installLocation
        + separator + "tools"
        + separator + "config-tool"
        + separator + "bin"
        + separator + "config-tool" + OS.INSTANCE.getShellExtension());
    command.addAll(Arrays.asList(arguments));
    LOGGER.info("Invoking config tool with args: {}", Arrays.asList(arguments));

    try {
      ProcessResult processResult = new ProcessExecutor(command)
          .directory(installLocation)
          .environment(buildEnv(tcEnv))
          .readOutput(true)
          .redirectErrorStream(true)
          .redirectOutputAlsoTo(Slf4jStream.of(ExternalLoggers.configToolLogger).asInfo())
          .redirectErrorAlsoTo(Slf4jStream.of(ExternalLoggers.configToolLogger).asError())
          .execute();
      return new ConfigToolExecutionResult(processResult.getExitValue(), processResult.getOutput().getLines());
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public URI tsaUri(Collection<TerracottaServer> servers, Map<ServerSymbolicName, Integer> proxyTsaPorts) {
    return URI.create(servers
        .stream()
        .map(s -> new HostPort(s.getHostname(), proxyTsaPorts.getOrDefault(s.getServerSymbolicName(), s.getTsaPort())).getHostPort())
        .collect(Collectors.joining(",", "terracotta://", "")));
  }

  @Override
  public String clientJarsRootFolderName(Distribution distribution) {
    if (distribution.getPackageType() == PackageType.KIT) {
      return "client";
    } else if (distribution.getPackageType() == PackageType.SAG_INSTALLER) {
      return "common" + separator + "lib";
    }
    throw new UnsupportedOperationException();
  }

  @Override
  public String pluginJarsRootFolderName(Distribution distribution) {
    return "server" + separator + "plugins" + separator + "lib";
  }

  private List<String> createTsaCommand(TerracottaServer terracottaServer, File installLocation, List<String> startUpArgs) {
    List<String> command = new ArrayList<>();
    command.add(getTsaCreateExecutable(installLocation));

    if (startUpArgs != null && !startUpArgs.isEmpty()) {
      command.addAll(startUpArgs);
    } else {
      List<String> dynamicArguments = addOptions(terracottaServer);
      command.addAll(dynamicArguments);
    }

    LOGGER.debug(" Create TSA command = {}", command);
    return command;
  }

  private List<String> addOptions(TerracottaServer server) {
    List<String> options = new ArrayList<>();

    if (server.getConfigFile() != null) {
      options.add("-f");
      options.add(server.getConfigFile());
    } else {
      // Add server name only if config file option wasn't provided
      options.add("-n");
      options.add(server.getServerSymbolicName().getSymbolicName());
    }

    // Add hostname
    options.add("-s");
    options.add(server.getHostname());

    if (server.getTsaPort() != 0) {
      options.add("-p");
      options.add(String.valueOf(server.getTsaPort()));
    }

    if (server.getTsaGroupPort() != 0) {
      options.add("-g");
      options.add(String.valueOf(server.getTsaGroupPort()));
    }

    if (server.getBindAddress() != null) {
      options.add("-a");
      options.add(server.getBindAddress());
    }

    if (server.getGroupBindAddress() != null) {
      options.add("-A");
      options.add(server.getGroupBindAddress());
    }

    if (server.getConfigRepo() != null) {
      options.add("-r");
      options.add(server.getConfigRepo());
    }

    if (server.getMetaData() != null) {
      options.add("-m");
      options.add(server.getMetaData());
    }

    if (server.getDataDir() != null) {
      options.add("-d");
      options.add(server.getDataDir());
    }

    if (server.getOffheap() != null) {
      options.add("-o");
      options.add(server.getOffheap());
    }

    if (server.getLogs() != null) {
      options.add("-L");
      options.add(server.getLogs());
    }

    if (server.getFailoverPriority() != null) {
      options.add("-y");
      options.add(server.getFailoverPriority());
    }

    if (server.getClientLeaseDuration() != null) {
      options.add("-i");
      options.add(server.getClientLeaseDuration());
    }

    LOGGER.info("Server startup options: {}", options);
    return options;
  }

  private String getTsaCreateExecutable(File installLocation) {
    String execPath = "server" + separator + "bin" + separator + "start-tc-server" + OS.INSTANCE.getShellExtension();
    if (distribution.getPackageType() == PackageType.KIT) {
      return installLocation.getAbsolutePath() + separator + execPath;
    } else if (distribution.getPackageType() == PackageType.SAG_INSTALLER) {
      return installLocation.getAbsolutePath() + separator + terracottaInstallationRoot() + separator + execPath;
    }
    throw new IllegalStateException("Can not define Terracotta server Start Command for distribution: " + distribution);
  }

  private List<String> startTmsCommand(File installLocation) {
    List<String> command = new ArrayList<>();
    command.add(getStartTmsExecutable(installLocation));
    LOGGER.debug(" Start TMS command = {}", command);
    return command;
  }

  private String getStartTmsExecutable(File installLocation) {
    String execPath = "tools" + separator + "management" + separator + "bin" + separator + "start" + OS.INSTANCE.getShellExtension();
    if (distribution.getPackageType() == PackageType.KIT) {
      return installLocation.getAbsolutePath() + separator + execPath;
    } else if (distribution.getPackageType() == PackageType.SAG_INSTALLER) {
      return installLocation.getAbsolutePath() + separator + terracottaInstallationRoot() + separator + execPath;
    }
    throw new IllegalStateException("Can not define TMS Start Command for distribution: " + distribution);
  }

  @Override
  public String terracottaInstallationRoot() {
    return "TerracottaDB";
  }
}