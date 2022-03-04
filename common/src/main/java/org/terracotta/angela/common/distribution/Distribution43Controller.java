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
import org.terracotta.angela.common.TerracottaCommandLineEnvironment;
import org.terracotta.angela.common.TerracottaManagementServerInstance.TerracottaManagementServerInstanceProcess;
import org.terracotta.angela.common.TerracottaServerHandle;
import org.terracotta.angela.common.TerracottaServerState;
import org.terracotta.angela.common.TerracottaVoter;
import org.terracotta.angela.common.TerracottaVoterInstance.TerracottaVoterInstanceProcess;
import org.terracotta.angela.common.ToolExecutionResult;
import org.terracotta.angela.common.provider.ConfigurationManager;
import org.terracotta.angela.common.provider.TcConfigManager;
import org.terracotta.angela.common.tcconfig.License;
import org.terracotta.angela.common.tcconfig.SecurityRootDirectory;
import org.terracotta.angela.common.tcconfig.ServerSymbolicName;
import org.terracotta.angela.common.tcconfig.TcConfig;
import org.terracotta.angela.common.tcconfig.TerracottaServer;
import org.terracotta.angela.common.tms.security.config.TmsServerSecurityConfig;
import org.terracotta.angela.common.topology.Topology;
import org.terracotta.angela.common.topology.Version;
import org.terracotta.angela.common.util.ExternalLoggers;
import org.terracotta.angela.common.util.HostPort;
import org.terracotta.angela.common.util.JavaBinaries;
import org.terracotta.angela.common.util.OS;
import org.terracotta.angela.common.util.ProcessUtil;
import org.terracotta.angela.common.util.RetryUtils;
import org.terracotta.angela.common.util.TriggeringOutputStream;
import org.zeroturnaround.exec.ProcessExecutor;
import org.zeroturnaround.exec.ProcessResult;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static java.io.File.separator;
import static java.util.regex.Pattern.compile;
import static org.terracotta.angela.common.AngelaProperties.TSA_FULL_LOGGING;
import static org.terracotta.angela.common.TerracottaServerState.STARTED_AS_ACTIVE;
import static org.terracotta.angela.common.TerracottaServerState.STARTED_AS_PASSIVE;
import static org.terracotta.angela.common.TerracottaServerState.STOPPED;
import static org.terracotta.angela.common.topology.PackageType.KIT;
import static org.terracotta.angela.common.topology.PackageType.SAG_INSTALLER;
import static org.terracotta.angela.common.util.HostAndIpValidator.isValidHost;
import static org.terracotta.angela.common.util.HostAndIpValidator.isValidIPv4;
import static org.terracotta.angela.common.util.HostAndIpValidator.isValidIPv6;

/**
 * @author Aurelien Broszniowski
 */
public class Distribution43Controller extends DistributionController {
  private final static Logger logger = LoggerFactory.getLogger(Distribution43Controller.class);

  private final boolean tsaFullLogging = TSA_FULL_LOGGING.getBooleanValue();

  Distribution43Controller(Distribution distribution) {
    super(distribution);
    Version version = distribution.getVersion();
    if (version.getMajor() != 4) {
      throw new IllegalStateException(getClass().getSimpleName() + " cannot work with distribution version " + version);
    }
  }

  @Override
  public TerracottaServerHandle createTsa(TerracottaServer terracottaServer, File kitDir, File workingDir,
                                          Topology topology, Map<ServerSymbolicName, Integer> proxiedPorts,
                                          TerracottaCommandLineEnvironment tcEnv, Map<String, String> envOverrides,
                                          List<String> startUpArgs, Duration inactivityKillerDelay) {
    AtomicReference<TerracottaServerState> stateRef = new AtomicReference<>(STOPPED);
    AtomicReference<TerracottaServerState> tempStateRef = new AtomicReference<>(STOPPED);

    TriggeringOutputStream serverLogOutputStream = TriggeringOutputStream
        .triggerOn(
            compile("^.*\\QTerracotta Server instance has started up as ACTIVE\\E.*$"),
            mr -> {
              if (stateRef.get() == STOPPED) {
                tempStateRef.set(STARTED_AS_ACTIVE);
              } else {
                stateRef.set(STARTED_AS_ACTIVE);
              }
            })
        .andTriggerOn(
            compile("^.*\\QMoved to State[ PASSIVE-STANDBY ]\\E.*$"),
            mr -> tempStateRef.set(STARTED_AS_PASSIVE))
        .andTriggerOn(
            compile("^.*\\QManagement server started\\E.*$"),
            mr -> stateRef.set(tempStateRef.get()))
        .andTriggerOn(
            compile("^.*\\QServer exiting\\E.*$"),
            mr -> stateRef.set(STOPPED));
    serverLogOutputStream = tsaFullLogging ?
        serverLogOutputStream.andForward(line -> ExternalLoggers.tsaLogger.info("[{}] {}", terracottaServer.getServerSymbolicName().getSymbolicName(), line)) :
        serverLogOutputStream.andTriggerOn(compile("^.*(WARN|ERROR).*$"), mr -> ExternalLoggers.tsaLogger.info("[{}] {}", terracottaServer.getServerSymbolicName().getSymbolicName(), mr.group()));

    // add an identifiable ID to the JVM's system properties
    Map<String, String> env = tcEnv.buildEnv(envOverrides);
    env.compute("JAVA_OPTS", (key, value) -> {
      String prop = " -Dangela.processIdentifier=" + terracottaServer.getId();
      return value == null ? prop : value + prop;
    });

    WatchedProcess<TerracottaServerState> watchedProcess = new WatchedProcess<>(
        new ProcessExecutor()
            .command(createTsaCommand(terracottaServer.getServerSymbolicName(), terracottaServer.getId(), topology.getConfigurationManager(), proxiedPorts, kitDir, workingDir, startUpArgs))
            .directory(workingDir)
            .environment(env)
            .redirectErrorStream(true)
            .redirectOutput(serverLogOutputStream),
        stateRef,
        STOPPED);

    Number javaPid = findWithJcmdJavaPidOf(terracottaServer.getId().toString(), tcEnv);
    return new TerracottaServerHandle() {
      @Override
      public TerracottaServerState getState() {
        return stateRef.get();
      }

      @Override
      public int getJavaPid() {
        return javaPid.intValue();
      }

      @Override
      public boolean isAlive() {
        return watchedProcess.isAlive();
      }

      @Override
      public void stop() {
        try {
          ProcessUtil.destroyGracefullyOrForcefullyAndWait(javaPid.intValue());
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

  private Number findWithJcmdJavaPidOf(String serverUuid, TerracottaCommandLineEnvironment tcEnv) {
    Path javaHome = tcEnv.getJavaHome();

    Path path = JavaBinaries.find("jcmd", javaHome).orElseThrow(() -> new IllegalStateException("jcmd not found"));

    List<String> cmdLine = new ArrayList<>();
    cmdLine.add(path.toAbsolutePath().toString());
    cmdLine.add("com.tc.server.TCServerMain");
    cmdLine.add("VM.system_properties");

    final int retries = 100;
    for (int i = 0; i < retries; i++) {
      ProcessResult processResult;
      try {
        processResult = new ProcessExecutor(cmdLine)
            .redirectErrorStream(true)
            .readOutput(true)
            .execute();
      } catch (Exception e) {
        logger.warn("Unable to get server pid with jcmd", e);
        return null;
      }

      if (processResult.getExitValue() == 0) {
        List<String> lines = processResult.getOutput().getLines();
        Number pid = parseOutputAndFindUuid(lines, serverUuid);
        if (pid != null) return pid;
      }

      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      }

      // warn on the last loop
      if (i == (retries - 1)) {
        logger.warn("Unable to get server pid with jcmd (rc={})", processResult.getExitValue());
        logger.warn("{}", processResult.getOutput().getString());
      }
    }

    return null;
  }

  private Number parseOutputAndFindUuid(List<String> lines, String serverUuid) {
    int pid = 0;
    for (String line : lines) {
      if (line.endsWith(":")) {
        try {
          pid = Integer.parseInt(line.substring(0, line.length() - 1));
        } catch (NumberFormatException e) {
          // false positive, skip
          continue;
        }
      }

      if (line.equals("angela.processIdentifier=" + serverUuid)) {
        return pid;
      }
    }
    logger.warn("Unable to parse jcmd output: {} to find serverUuid {}", lines, serverUuid);
    return null;
  }

  @Override
  public ToolExecutionResult configureCluster(File kitDir, File workingDir, Topology topology, Map<ServerSymbolicName, Integer> proxyTsaPorts, License license, SecurityRootDirectory securityDir,
                                              TerracottaCommandLineEnvironment env, Map<String, String> envOverrides, String... arguments) {
    throw new UnsupportedOperationException("Running cluster tool is not supported in this distribution version");
  }

  @Override
  public ToolExecutionResult activateCluster(File kitDir, File workingDir, License license, SecurityRootDirectory securityDir,
                                             TerracottaCommandLineEnvironment env, Map<String, String> envOverrides, String... arguments) {
    throw new UnsupportedOperationException("Running config tool is not supported in this distribution version");
  }

  @Override
  public ToolExecutionResult invokeClusterTool(File kitDir, File workingDir, SecurityRootDirectory securityDir,
                                               TerracottaCommandLineEnvironment env, Map<String, String> envOverrides, String... arguments) {
    throw new UnsupportedOperationException("Running cluster tool is not supported in this distribution version");
  }

  @Override
  public ToolExecutionResult invokeConfigTool(File kitDir, File workingDir, SecurityRootDirectory securityDir,
                                              TerracottaCommandLineEnvironment env, Map<String, String> envOverrides, String... arguments) {
    throw new UnsupportedOperationException("Running config tool is not supported in this distribution version");
  }

  /**
   * Construct the Start Command with the Version, Tc Config file and server name
   *
   * @return List of Strings representing the start command and its parameters
   */
  private List<String> createTsaCommand(ServerSymbolicName serverSymbolicName, UUID serverId, ConfigurationManager configurationManager,
                                        Map<ServerSymbolicName, Integer> proxiedPorts, File kitLocation, File installLocation,
                                        List<String> startUpArgs) {
    List<String> options = new ArrayList<>();
    // start command
    options.add(getStartCmd(kitLocation));

    String symbolicName = serverSymbolicName.getSymbolicName();
    if (isValidHost(symbolicName) || isValidIPv4(symbolicName) || isValidIPv6(symbolicName) || symbolicName.isEmpty()) {
      // add -n if applicable
      options.add("-n");
      options.add(symbolicName);
    }

    TcConfigManager tcConfigProvider = (TcConfigManager) configurationManager;
    TcConfig tcConfig = tcConfigProvider.findTcConfig(serverId).copy();
    tcConfigProvider.setUpInstallation(tcConfig, serverSymbolicName, serverId, proxiedPorts, installLocation, null);
    // add -f if applicable
    if (tcConfig.getPath() != null) {
      //workaround to have unique platform restart directory for active & passives
      //TODO this can  be removed when platform persistent has server name in the path
      try {
        String modifiedTcConfigPath = tcConfig.getPath()
            .substring(0, tcConfig.getPath()
                .length() - 4) + "-" + serverSymbolicName.getSymbolicName() + ".xml";
        String modifiedConfig = FileUtils.readFileToString(new File(tcConfig.getPath()), StandardCharsets.UTF_8).
            replaceAll(Pattern.quote("${restart-data}"), "restart-data/" + serverSymbolicName).
            replaceAll(Pattern.quote("${SERVER_NAME_TEMPLATE}"), serverSymbolicName.getSymbolicName());
        FileUtils.write(new File(modifiedTcConfigPath), modifiedConfig, StandardCharsets.UTF_8);
        options.add("-f");
        options.add(modifiedTcConfigPath);
      } catch (IOException ioe) {
        throw new RuntimeException("Error when modifying tc config", ioe);
      }
    }

    options.addAll(startUpArgs);
    StringBuilder sb = new StringBuilder();
    for (String option : options) {
      sb.append(option).append(" ");
    }
    logger.debug("TSA create command = {}", sb.toString());

    return options;
  }

  private String getStartCmd(File kitLocation) {
    String execPath = "server" + separator + "bin" + separator + "start-tc-server" + OS.INSTANCE.getShellExtension();
    if (distribution.getPackageType() == KIT) {
      return kitLocation.getAbsolutePath() + separator + execPath;
    } else if (distribution.getPackageType() == SAG_INSTALLER) {
      return kitLocation.getAbsolutePath() + separator + terracottaInstallationRoot() + separator + execPath;
    }
    throw new IllegalStateException("Can not define Terracotta server Start Command for distribution: " + distribution);
  }

  @Override
  public TerracottaManagementServerInstanceProcess startTms(File kitDir, File workingDir, TerracottaCommandLineEnvironment tcEnv, Map<String, String> envOverrides) {
    throw new UnsupportedOperationException("NOT YET IMPLEMENTED");
  }

  @Override
  public void stopTms(File installLocation, TerracottaManagementServerInstanceProcess terracottaServerInstanceProcess, TerracottaCommandLineEnvironment tcEnv) {
    throw new UnsupportedOperationException("NOT YET IMPLEMENTED");
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
        .map(s -> new HostPort(s.getHostName(), proxyTsaPorts.getOrDefault(s.getServerSymbolicName(), s.getTsaPort())).getHostPort())
        .collect(Collectors.joining(",", "", "")));
  }

  @Override
  public String clientJarsRootFolderName(Distribution distribution) {
    if (distribution.getPackageType() == KIT) {
      return "apis";
    } else if (distribution.getPackageType() == SAG_INSTALLER) {
      return "common" + separator + "lib";
    }
    throw new UnsupportedOperationException();
  }

  @Override
  public String pluginJarsRootFolderName(Distribution distribution) {
    throw new UnsupportedOperationException("4.x does not support plugins");
  }

  @Override
  public String terracottaInstallationRoot() {
    return "Terracotta";
  }

  @Override
  public void prepareTMS(File kitDir, File workingDir, TmsServerSecurityConfig tmsServerSecurityConfig) {
  }
}
