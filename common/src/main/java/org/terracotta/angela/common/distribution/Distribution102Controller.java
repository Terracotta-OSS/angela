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
package org.terracotta.angela.common.distribution;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terracotta.angela.common.AngelaProperties;
import org.terracotta.angela.common.TerracottaCommandLineEnvironment;
import org.terracotta.angela.common.TerracottaManagementServerInstance.TerracottaManagementServerInstanceProcess;
import org.terracotta.angela.common.TerracottaManagementServerState;
import org.terracotta.angela.common.TerracottaServerHandle;
import org.terracotta.angela.common.TerracottaServerState;
import org.terracotta.angela.common.TerracottaVoter;
import org.terracotta.angela.common.TerracottaVoterInstance.TerracottaVoterInstanceProcess;
import org.terracotta.angela.common.ToolExecutionResult;
import org.terracotta.angela.common.provider.ConfigurationManager;
import org.terracotta.angela.common.provider.TcConfigManager;
import org.terracotta.angela.common.tcconfig.License;
import org.terracotta.angela.common.tcconfig.SecureTcConfig;
import org.terracotta.angela.common.tcconfig.SecurityRootDirectory;
import org.terracotta.angela.common.tcconfig.ServerSymbolicName;
import org.terracotta.angela.common.tcconfig.TcConfig;
import org.terracotta.angela.common.tcconfig.TerracottaServer;
import org.terracotta.angela.common.topology.PackageType;
import org.terracotta.angela.common.topology.Topology;
import org.terracotta.angela.common.util.ExternalLoggers;
import org.terracotta.angela.common.util.HostPort;
import org.terracotta.angela.common.util.OS;
import org.terracotta.angela.common.util.ProcessUtil;
import org.terracotta.angela.common.util.RetryUtils;
import org.terracotta.angela.common.util.TriggeringOutputStream;
import org.zeroturnaround.exec.ProcessExecutor;
import org.zeroturnaround.exec.ProcessResult;
import org.zeroturnaround.exec.stream.slf4j.Slf4jStream;

import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static java.io.File.separator;
import static java.lang.Integer.parseInt;
import static java.util.regex.Pattern.compile;
import static org.terracotta.angela.common.util.HostAndIpValidator.isValidHost;
import static org.terracotta.angela.common.util.HostAndIpValidator.isValidIPv4;
import static org.terracotta.angela.common.util.HostAndIpValidator.isValidIPv6;

/**
 * @author Aurelien Broszniowski
 */
public class Distribution102Controller extends DistributionController {
  private final static Logger logger = LoggerFactory.getLogger(Distribution102Controller.class);
  private final boolean tsaFullLogging = AngelaProperties.TSA_FULL_LOGGING.getBooleanValue();
  private final boolean tmsFullLogging = AngelaProperties.TMS_FULL_LOGGING.getBooleanValue();

  Distribution102Controller(Distribution distribution) {
    super(distribution);
  }

  @Override
  public TerracottaServerHandle createTsa(TerracottaServer terracottaServer, File kitDir, File workingDir,
                                                   Topology topology, Map<ServerSymbolicName, Integer> proxiedPorts,
                                                   TerracottaCommandLineEnvironment tcEnv, Map<String, String> envOverrides, List<String> startUpArgs) {
    Map<String, String> env = tcEnv.buildEnv(envOverrides);
    AtomicReference<TerracottaServerState> stateRef = new AtomicReference<>(TerracottaServerState.STOPPED);
    AtomicInteger javaPid = new AtomicInteger(-1);

    TriggeringOutputStream serverLogOutputStream = TriggeringOutputStream
        .triggerOn(
            compile("^.*\\QTerracotta Server instance has started up as ACTIVE\\E.*$"),
            mr -> stateRef.set(TerracottaServerState.STARTED_AS_ACTIVE))
        .andTriggerOn(
            compile("^.*\\QMoved to State[ PASSIVE-STANDBY ]\\E.*$"),
            mr -> stateRef.set(TerracottaServerState.STARTED_AS_PASSIVE))
        .andTriggerOn(
            compile("^.*\\QL2 Exiting\\E.*$"),
            mr -> stateRef.set(TerracottaServerState.STOPPED))
        .andTriggerOn(
            compile("^.*PID is (\\d+).*$"), mr -> {
              javaPid.set(parseInt(mr.group(1)));
              stateRef.compareAndSet(TerracottaServerState.STOPPED, TerracottaServerState.STARTING);
            });
    serverLogOutputStream = tsaFullLogging ?
        serverLogOutputStream.andForward(line -> ExternalLoggers.tsaLogger.info("[{}] {}", terracottaServer.getServerSymbolicName().getSymbolicName(), line)) :
        serverLogOutputStream.andTriggerOn(compile("^.*(WARN|ERROR).*$"), mr -> ExternalLoggers.tsaLogger.info("[{}] {}", terracottaServer.getServerSymbolicName().getSymbolicName(), mr.group()));

    WatchedProcess<TerracottaServerState> watchedProcess = new WatchedProcess<>(
        new ProcessExecutor()
            .command(createTsaCommand(terracottaServer.getServerSymbolicName(), terracottaServer.getId(), topology, proxiedPorts, kitDir, workingDir, startUpArgs))
            .directory(workingDir)
            .environment(env)
            .redirectErrorStream(true)
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

    return new TerracottaServerHandle() {
      @Override
      public TerracottaServerState getState() {
        return stateRef.get();
      }

      @Override
      public int getJavaPid() {
        return javaPid.get();
      }

      @Override
      public boolean isAlive() {
        return watchedProcess.isAlive();
      }

      @Override
      public void stop() {
        try {
          ProcessUtil.destroyGracefullyOrForcefullyAndWait(javaPid.get());
        } catch (Exception e) {
          throw new RuntimeException("Could not destroy TC server process with PID " + watchedProcess.getPid(), e);
        }
        try {
          ProcessUtil.destroyGracefullyOrForcefullyAndWait(watchedProcess.getPid());
        } catch (Exception e) {
          throw new RuntimeException("Could not destroy TC server process with PID " + watchedProcess.getPid(), e);
        }
        final int maxWaitTimeMillis = 30000;
        if (!RetryUtils.waitFor(() -> getState() == TerracottaServerState.STOPPED, maxWaitTimeMillis)) {
          throw new RuntimeException(
              String.format(
                  "Tried for %dms, but server %s did not get the state %s [remained at state %s]",
                  maxWaitTimeMillis,
                  terracottaServer.getServerSymbolicName().getSymbolicName(),
                  TerracottaServerState.STOPPED,
                  getState()
              )
          );
        }
      }
    };
  }

  @Override
  public ToolExecutionResult configureCluster(File kitDir, File workingDir, Topology topology, Map<ServerSymbolicName, Integer> proxyTsaPorts, License license, SecurityRootDirectory securityDir,
                                              TerracottaCommandLineEnvironment env, Map<String, String> envOverrides, String... arguments) {
    List<String> command = createClusterConfigureCommand(kitDir, workingDir, topology, proxyTsaPorts, license, securityDir, arguments);
    return executeCommand(command, env, workingDir, envOverrides);
  }

  @Override
  public ToolExecutionResult invokeClusterTool(File kitDir, File workingDir, SecurityRootDirectory securityDir,
                                               TerracottaCommandLineEnvironment env, Map<String, String> envOverrides, String... arguments) {
    List<String> command = createClusterToolCommand(kitDir, workingDir, securityDir, arguments);
    return executeCommand(command, env, workingDir, envOverrides);
  }

  @Override
  public ToolExecutionResult invokeConfigTool(File kitDir, File workingDir, SecurityRootDirectory securityDir,
                                              TerracottaCommandLineEnvironment env, Map<String, String> envOverrides, String... arguments) {
    throw new UnsupportedOperationException("Running config tool is not supported in this distribution version");
  }

  @Override
  public ToolExecutionResult activateCluster(File kitDir, File workingDir, License license, SecurityRootDirectory securityDir,
                                             TerracottaCommandLineEnvironment env, Map<String, String> envOverrides, String... arguments) {
    throw new UnsupportedOperationException("Running config tool is not supported in this distribution version");
  }

  @Override
  public TerracottaManagementServerInstanceProcess startTms(File kitDir, File workingDir, TerracottaCommandLineEnvironment tcEnv, Map<String, String> envOverrides) {
    Map<String, String> env = tcEnv.buildEnv(envOverrides);

    AtomicReference<TerracottaManagementServerState> stateRef = new AtomicReference<>(TerracottaManagementServerState.STOPPED);
    AtomicInteger javaPid = new AtomicInteger(-1);

    TriggeringOutputStream outputStream = TriggeringOutputStream
        .triggerOn(
            compile("^.*\\Qstarted on port\\E.*$"),
            mr -> stateRef.set(TerracottaManagementServerState.STARTED))
        .andTriggerOn(
            compile("^.*\\QStarting TmsApplication\\E.*with PID (\\d+).*$"),
            mr -> javaPid.set(parseInt(mr.group(1))));
    outputStream = tmsFullLogging ?
        outputStream.andForward(ExternalLoggers.tmsLogger::info) :
        outputStream.andTriggerOn(compile("^.*(WARN|ERROR).*$"), mr -> ExternalLoggers.tmsLogger.info(mr.group()));

    WatchedProcess<TerracottaManagementServerState> watchedProcess = new WatchedProcess<>(new ProcessExecutor()
        .command(startTmsCommand(kitDir))
        .directory(workingDir)
        .environment(env)
        .redirectErrorStream(true)
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
    logger.debug("Destroying TMS process");
    for (Number pid : terracottaServerInstanceProcess.getPids()) {
      try {
        ProcessUtil.destroyGracefullyOrForcefullyAndWait(pid.intValue());
      } catch (Exception e) {
        logger.error("Could not destroy TMS process {}", pid, e);
      }
    }
  }

  @Override
  public TerracottaVoterInstanceProcess startVoter(TerracottaVoter terracottaVoter, File kitDir, File workingDir,
                                                   SecurityRootDirectory securityDir, TerracottaCommandLineEnvironment tcEnv, Map<String, String> envOverrides) {
    throw new UnsupportedOperationException("Running voter is not supported in this distribution version");
  }

  @Override
  public void stopVoter(TerracottaVoterInstanceProcess terracottaVoterInstanceProcess) {
    throw new UnsupportedOperationException("Running voter is not supported in this distribution version");
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

  @Override
  public String terracottaInstallationRoot() {
    return "TerracottaDB";
  }

  private List<String> createClusterToolCommand(File installLocation, File workingDir, SecurityRootDirectory securityDir, String[] arguments) {
    List<String> command = new ArrayList<>();
    command.add(getClusterToolExecutable(installLocation));
    if (securityDir != null) {
      Path securityDirPath = workingDir.toPath().resolve("cluster-tool-security-dir");
      securityDir.createSecurityRootDirectory(securityDirPath);
      command.add("-srd");
      command.add(securityDirPath.toString());
    }
    command.addAll(Arrays.asList(arguments));
    return command;
  }

  private List<String> createClusterConfigureCommand(File installLocation, File workingDir, Topology topology, Map<ServerSymbolicName, Integer> proxyTsaPort, License license, SecurityRootDirectory securityDir, String[] arguments) {
    List<String> command = createClusterToolCommand(installLocation, workingDir, securityDir, arguments);
    if (license != null) {
      Path licensePath = workingDir.toPath().resolve(license.getFilename());
      command.add("-l");
      command.add(licensePath.toString());
    }
    command.addAll(addConfigureRelatedCommands(topology, proxyTsaPort));
    return command;
  }

  private List<String> addConfigureRelatedCommands(Topology topology, Map<ServerSymbolicName, Integer> proxiedTsaPorts) {
    List<String> commands = new ArrayList<>();
    File tmpConfigDir = new File(FileUtils.getTempDirectory(), "tmp-tc-configs");

    if (!tmpConfigDir.mkdir() && !tmpConfigDir.isDirectory()) {
      throw new RuntimeException("Error creating temporary cluster tool TC config folder : " + tmpConfigDir);
    }
    ConfigurationManager configurationProvider = topology.getConfigurationManager();
    TcConfigManager tcConfigProvider = (TcConfigManager) configurationProvider;
    List<TcConfig> tcConfigs = tcConfigProvider.getTcConfigs();
    List<TcConfig> modifiedConfigs = new ArrayList<>();
    for (TcConfig tcConfig : tcConfigs) {
      TcConfig modifiedConfig = tcConfig.copy();
      if (topology.isNetDisruptionEnabled()) {
        modifiedConfig.updateServerTsaPort(proxiedTsaPorts);
      }
      modifiedConfig.writeTcConfigFile(tmpConfigDir);
      modifiedConfigs.add(modifiedConfig);
    }

    for (TcConfig tcConfig : modifiedConfigs) {
      commands.add(tcConfig.getPath());
    }
    return commands;
  }

  private ToolExecutionResult executeCommand(List<String> command, TerracottaCommandLineEnvironment env,
                                             File workingDir, Map<String, String> envOverrides) {
    try {
      logger.debug("Cluster tool command: {}", command);
      ProcessResult processResult = new ProcessExecutor(command)
          .directory(workingDir)
          .environment(env.buildEnv(envOverrides))
          .readOutput(true)
          .redirectOutputAlsoTo(Slf4jStream.of(ExternalLoggers.clusterToolLogger).asInfo())
          .redirectErrorStream(true)
          .execute();
      return new ToolExecutionResult(processResult.getExitValue(), processResult.getOutput().getLines());
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private String getClusterToolExecutable(File installLocation) {
    String execPath = "tools" + separator + "cluster-tool" + separator + "bin" + separator + "cluster-tool" + OS.INSTANCE.getShellExtension();

    if (distribution.getPackageType() == PackageType.KIT) {
      return installLocation.getAbsolutePath() + separator + execPath;
    } else if (distribution.getPackageType() == PackageType.SAG_INSTALLER) {
      return installLocation.getAbsolutePath() + separator + terracottaInstallationRoot() + separator + execPath;
    }
    throw new IllegalStateException("Can not define cluster tool command for distribution: " + distribution);
  }

  private List<String> startTmsCommand(File kitDir) {
    List<String> options = new ArrayList<>();
    // start command
    options.add(getStartTmsExecutable(kitDir));

    StringBuilder sb = new StringBuilder();
    for (String option : options) {
      sb.append(option).append(" ");
    }
    logger.debug(" Start TMS command = {}", sb.toString());

    return options;
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

  private List<String> createTsaCommand(ServerSymbolicName serverSymbolicName, UUID serverId, Topology topology,
                                        Map<ServerSymbolicName, Integer> proxiedPorts, File kitLocation, File installLocation,
                                        List<String> startUpArgs) {
    List<String> options = new ArrayList<>();
    // start command
    options.add(getTsaCreateExecutable(kitLocation));

    String symbolicName = serverSymbolicName.getSymbolicName();
    if (isValidHost(symbolicName) || isValidIPv4(symbolicName) || isValidIPv6(symbolicName) || symbolicName.isEmpty()) {
      // add -n if applicable
      options.add("-n");
      options.add(symbolicName);
    }

    TcConfigManager configurationProvider = (TcConfigManager) topology.getConfigurationManager();
    TcConfig tcConfig = configurationProvider.findTcConfig(serverId);
    SecurityRootDirectory securityRootDirectory = null;
    if (tcConfig instanceof SecureTcConfig) {
      SecureTcConfig secureTcConfig = (SecureTcConfig) tcConfig;
      securityRootDirectory = secureTcConfig.securityRootDirectoryFor(serverSymbolicName);
    }
    TcConfig modifiedConfig = configurationProvider.findTcConfig(serverId).copy();
    configurationProvider.setUpInstallation(modifiedConfig, serverSymbolicName, serverId, proxiedPorts, installLocation, securityRootDirectory);

    // add -f if applicable
    if (modifiedConfig.getPath() != null) {
      options.add("-f");
      options.add(modifiedConfig.getPath());
    }

    options.addAll(startUpArgs);

    StringBuilder sb = new StringBuilder();
    for (String option : options) {
      sb.append(option).append(" ");
    }
    logger.debug("Create TSA command = {}", sb.toString());

    return options;
  }

  private String getTsaCreateExecutable(File kitLocation) {
    String execPath = "server" + separator + "bin" + separator + "start-tc-server" + OS.INSTANCE.getShellExtension();
    if (distribution.getPackageType() == PackageType.KIT) {
      return kitLocation.getAbsolutePath() + separator + execPath;
    } else if (distribution.getPackageType() == PackageType.SAG_INSTALLER) {
      return kitLocation.getAbsolutePath() + separator + terracottaInstallationRoot() + separator + execPath;
    }
    throw new IllegalStateException("Can not define Terracotta server Start Command for distribution: " + distribution);
  }
}
