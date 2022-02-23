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
import org.terracotta.angela.common.TerracottaVoterState;
import org.terracotta.angela.common.ToolExecutionResult;
import org.terracotta.angela.common.tcconfig.License;
import org.terracotta.angela.common.tcconfig.SecurityRootDirectory;
import org.terracotta.angela.common.tcconfig.ServerSymbolicName;
import org.terracotta.angela.common.tcconfig.TerracottaServer;
import org.terracotta.angela.common.tms.security.config.TmsServerSecurityConfig;
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
import java.io.IOException;
import java.io.Writer;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static java.io.File.separator;
import static java.io.File.separatorChar;
import static java.lang.Integer.parseInt;
import static java.lang.String.join;
import static java.util.regex.Pattern.compile;
import static org.terracotta.angela.common.AngelaProperties.TMS_FULL_LOGGING;
import static org.terracotta.angela.common.AngelaProperties.TSA_FULL_LOGGING;
import static org.terracotta.angela.common.AngelaProperties.VOTER_FULL_LOGGING;

public class Distribution107Controller extends DistributionController {
  private final static Logger LOGGER = LoggerFactory.getLogger(Distribution107Controller.class);
  private final boolean tsaFullLogging = TSA_FULL_LOGGING.getBooleanValue();
  private final boolean tmsFullLogging = TMS_FULL_LOGGING.getBooleanValue();
  private final boolean voterFullLogging = VOTER_FULL_LOGGING.getBooleanValue();

  public Distribution107Controller(Distribution distribution) {
    super(distribution);
  }

  @Override
  public TerracottaServerHandle createTsa(TerracottaServer terracottaServer, File kitDir, File workingDir,
                                          Topology topology, Map<ServerSymbolicName, Integer> proxiedPorts,
                                          TerracottaCommandLineEnvironment tcEnv, Map<String, String> envOverrides, List<String> startUpArgs) {
    Map<String, String> env = tcEnv.buildEnv(envOverrides);
    Timer timer = new Timer(terracottaServer.getServerSymbolicName().getSymbolicName() + "-watchguard", true);
    AtomicReference<TerracottaServerState> stateRef = new AtomicReference<>(TerracottaServerState.STOPPED);
    Consumer<TerracottaServerState> stateUpdater = newState -> {
      stateRef.set(newState);
      if (newState != TerracottaServerState.STARTING && newState != TerracottaServerState.NOT_INSTALLED) {
        timer.cancel();
      }
    };
    AtomicInteger javaPid = new AtomicInteger(-1);
    AtomicReference<Writer> stdout = new AtomicReference<>();
    AtomicReference<WatchedProcess<TerracottaServerState>> process = new AtomicReference<>();
    TerracottaServerHandle terracottaServerHandle = new TerracottaServerHandle() {
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
        return process.get().isAlive();
      }

      @Override
      public void stop() {
        try {
          ProcessUtil.destroyGracefullyOrForcefullyAndWait(javaPid.get());
        } catch (Exception e) {
          throw new RuntimeException("Could not destroy TC server process with PID " + process.get().getPid(), e);
        }
        try {
          ProcessUtil.destroyGracefullyOrForcefullyAndWait(process.get().getPid());
        } catch (Exception e) {
          throw new RuntimeException("Could not destroy TC server process with PID " + process.get().getPid(), e);
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
        if (stdout.get() != null) {
          try {
            stdout.get().close();
          } catch (IOException ignored) {
          }
        }
      }
    };
    // this timer will kill the TSA process if it didn't reach another state within 30 sec.
    // this can happen in case of a bug preventing the server to exit if a thread is not interrupted.
    // this issue can be seen in the case of service provider initialization failure which cause the server
    // to stop, main thread is gone, but SingleThreadedTimer has a waiting thread preventing the server to exit
    TimerTask task = new TimerTask() {
      @Override
      public void run() {
        LOGGER.error("**************************");
        LOGGER.error("POSSIBLE BUG IN CORE");
        LOGGER.error("**************************");
        LOGGER.error("TSA PROCESS WITH PID: {} WILL BE KILLED", javaPid.get());
        LOGGER.error("It seems to be stuck starting, and it could not reach another state within 20 seconds");
        LOGGER.error("It is possible that server startup has failed because of an exception, leaving some threads behind preventing the server to exit properly.");
        LOGGER.error("Use angela.tsa.watch=false to disable this watch guard. This might make the test block, and you will be able to use jps and jstack.");
        LOGGER.error("**************************");
        terracottaServerHandle.stop();
      }
    };

    TriggeringOutputStream serverLogOutputStream = TriggeringOutputStream
        .triggerOn(
            compile("^.*\\QStarted the server in diagnostic mode\\E.*$"),
            mr -> stateUpdater.accept(TerracottaServerState.STARTED_IN_DIAGNOSTIC_MODE))
        .andTriggerOn(
            compile("^.*\\QTerracotta Server instance has started up as ACTIVE\\E.*$"),
            mr -> stateUpdater.accept(TerracottaServerState.STARTED_AS_ACTIVE))
        .andTriggerOn(
            compile("^.*\\QMoved to State[ PASSIVE-STANDBY ]\\E.*$"),
            mr -> stateUpdater.accept(TerracottaServerState.STARTED_AS_PASSIVE))
        .andTriggerOn(
            compile("^.*\\QMOVE_TO_ACTIVE not allowed because not enough servers are connected\\E.*$"),
            mr -> stateUpdater.accept(TerracottaServerState.START_SUSPENDED))
        .andTriggerOn(
            compile("^.*PID is (\\d+).*$"),
            mr -> {
              javaPid.set(parseInt(mr.group(1)));
              stateRef.compareAndSet(TerracottaServerState.STOPPED, TerracottaServerState.STARTING);
              timer.schedule(task, 20_000);
            });

    try {
      stdout.set(Files.newBufferedWriter(workingDir.toPath().resolve("stdout.txt"), StandardOpenOption.CREATE, StandardOpenOption.APPEND));
      serverLogOutputStream = serverLogOutputStream.andForward(line -> {
        try {
          stdout.get().write(line);
          stdout.get().append('\n');
          stdout.get().flush();
        } catch (IOException io) {
          LOGGER.warn("failed to write to stdout file", io);
        }
      });
    } catch (IOException io) {
      LOGGER.warn("failed to create stdout file", io);
      serverLogOutputStream = tsaFullLogging ?
          serverLogOutputStream.andForward(line -> ExternalLoggers.tsaLogger.info("[{}] {}", terracottaServer.getServerSymbolicName().getSymbolicName(), line)) :
          serverLogOutputStream.andTriggerOn(compile("^.*(WARN|ERROR).*$"), mr -> ExternalLoggers.tsaLogger.info("[{}] {}", terracottaServer.getServerSymbolicName().getSymbolicName(), mr.group()));
    }

    process.set(new WatchedProcess<>(
        new ProcessExecutor()
            .command(createTsaCommand(terracottaServer, kitDir, workingDir, startUpArgs))
            .directory(workingDir)
            .environment(env)
            .redirectErrorStream(true)
            .redirectOutput(serverLogOutputStream),
        stateUpdater,
        TerracottaServerState.STOPPED));

    while (javaPid.get() == -1 && process.get().isAlive()) {
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      }
    }

    if (!process.get().isAlive()) {
      throw new RuntimeException("Terracotta server process died in its infancy : " + terracottaServer.getServerSymbolicName());
    }

    return terracottaServerHandle;
  }

  @Override
  public TerracottaManagementServerInstanceProcess startTms(File kitDir, File workingDir, TerracottaCommandLineEnvironment tcEnv, Map<String, String> envOverrides) {
    Map<String, String> env = tcEnv.buildEnv(envOverrides);

    if (!AngelaProperties.KIT_COPY.getBooleanValue() && !env.containsKey("TMC_HOME")) {
      // when using the kit to start from, we have our config in the angela working dir.
      // we have to point to the right working dir to not modify files inside the kit
      env.put("TMC_HOME", workingDir.getAbsolutePath());
    }

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
        .redirectOutput(outputStream), stateRef::set, TerracottaManagementServerState.STOPPED);

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
        // TMS does not close correctly in 30 sec, so process is killed. The state is not correctly set to STOPEPD after.
        terracottaServerInstanceProcess.setState(TerracottaManagementServerState.STOPPED);
      } catch (Exception e) {
        LOGGER.error("Could not destroy TMS process {}", pid, e);
      }
    }
  }

  @Override
  public TerracottaVoterInstanceProcess startVoter(TerracottaVoter terracottaVoter, File kitDir, File workingDir,
                                                   SecurityRootDirectory securityDir, TerracottaCommandLineEnvironment tcEnv, Map<String, String> envOverrides) {
    Map<String, String> env = tcEnv.buildEnv(envOverrides);

    AtomicReference<TerracottaVoterState> stateRef = new AtomicReference<>(TerracottaVoterState.STOPPED);
    AtomicInteger javaPid = new AtomicInteger(-1);

    TriggeringOutputStream outputStream = TriggeringOutputStream
        .triggerOn(
            compile("^.*PID is (\\d+).*$"),
            mr -> {
              javaPid.set(parseInt(mr.group(1)));
              stateRef.compareAndSet(TerracottaVoterState.STOPPED, TerracottaVoterState.STARTED);
            })
        .andTriggerOn(
            compile("^.*\\QVote owner state: ACTIVE-COORDINATOR\\E.*$"),
            mr -> stateRef.compareAndSet(TerracottaVoterState.STARTED, TerracottaVoterState.CONNECTED_TO_ACTIVE))
        .andTriggerOn(voterFullLogging ? compile("^.*$") : compile("^.*(WARN|ERROR).*$"),
            mr -> ExternalLoggers.voterLogger.info("[{}] {}", terracottaVoter.getId(), mr.group()));

    WatchedProcess<TerracottaVoterState> watchedProcess = new WatchedProcess<>(
        new ProcessExecutor()
            .command(startVoterCommand(kitDir, workingDir, securityDir, terracottaVoter))
            .directory(workingDir)
            .environment(env)
            .redirectErrorStream(true)
            .redirectOutput(outputStream),
        stateRef::set,
        TerracottaVoterState.STOPPED);

    while ((javaPid.get() == -1 || stateRef.get() == TerracottaVoterState.STOPPED) && watchedProcess.isAlive()) {
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      }
    }
    if (!watchedProcess.isAlive()) {
      throw new RuntimeException("Voter process died before reaching STARTED state");
    }

    int wrapperPid = watchedProcess.getPid();
    int javaProcessPid = javaPid.get();
    return new TerracottaVoterInstanceProcess(stateRef, wrapperPid, javaProcessPid);
  }

  @Override
  public void stopVoter(TerracottaVoterInstanceProcess terracottaVoterInstanceProcess) {
    LOGGER.debug("Destroying Voter process");
    for (Number pid : terracottaVoterInstanceProcess.getPids()) {
      try {
        ProcessUtil.destroyGracefullyOrForcefullyAndWait(pid.intValue());
      } catch (Exception e) {
        LOGGER.error("Could not destroy voter process {}", pid, e);
      }
    }
  }

  @Override
  public ToolExecutionResult invokeClusterTool(File kitDir, File workingDir, SecurityRootDirectory securityDir,
                                               TerracottaCommandLineEnvironment env, Map<String, String> envOverrides, String... arguments) {
    try {
      ProcessResult processResult = new ProcessExecutor(createClusterToolCommand(kitDir, workingDir, securityDir, arguments))
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

  @Override
  public ToolExecutionResult configureCluster(File kitDir, File workingDir, Topology topology, Map<ServerSymbolicName, Integer> proxyTsaPorts, License license, SecurityRootDirectory securityDir,
                                              TerracottaCommandLineEnvironment env, Map<String, String> envOverrides, String... arguments) {
    throw new UnsupportedOperationException("Running cluster tool configure is not supported in this distribution version");
  }

  @Override
  public ToolExecutionResult invokeConfigTool(File kitDir, File workingDir, SecurityRootDirectory securityDir,
                                              TerracottaCommandLineEnvironment env, Map<String, String> envOverrides, String... arguments) {
    List<String> command = createConfigToolCommand(kitDir, workingDir, securityDir, arguments);
    return executeCommand(command, env, workingDir, envOverrides);
  }

  @Override
  public ToolExecutionResult activateCluster(File kitDir, File workingDir, License license, SecurityRootDirectory securityDir,
                                             TerracottaCommandLineEnvironment env, Map<String, String> envOverrides, String... arguments) {
    List<String> command = createActivateCommand(kitDir, workingDir, license, securityDir, arguments);
    return executeCommand(command, env, workingDir, envOverrides);
  }

  @Override
  public URI tsaUri(Collection<TerracottaServer> servers, Map<ServerSymbolicName, Integer> proxyTsaPorts) {
    return URI.create(servers
        .stream()
        .map(s -> new HostPort(s.getHostName(), proxyTsaPorts.getOrDefault(s.getServerSymbolicName(), s.getTsaPort())).getHostPort())
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

  List<String> createTsaCommand(TerracottaServer terracottaServer, File kitLocation, File workingDir, List<String> startUpArgs) {
    List<String> command = new ArrayList<>();
    command.add(getTsaCreateExecutable(kitLocation));

    if (startUpArgs != null && !startUpArgs.isEmpty()) {
      command.addAll(startUpArgs);
    } else {
      try {
        List<String> dynamicArguments = addOptions(terracottaServer, workingDir);
        command.addAll(dynamicArguments);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }

    LOGGER.debug("Create TSA command: {}", command);
    return command;
  }

  private List<String> addOptions(TerracottaServer server, File workingDir) throws IOException {
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
    options.add(server.getHostName());

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

    if (server.getDataDir().size() != 0) {
      options.add("-d");
      options.add(join(",", server.getDataDir()));
    }

    if (server.getOffheap().size() != 0) {
      options.add("-o");
      options.add(join(",", server.getOffheap()));
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

    if (server.getClientReconnectWindow() != null) {
      options.add("-R");
      options.add(server.getClientReconnectWindow());
    }

    if (server.getBackupDir() != null) {
      options.add("-b");
      options.add(server.getBackupDir());
    }

    if (server.getAuditLogDir() != null) {
      options.add("-u");
      String auditPath = workingDir.getAbsolutePath() + separatorChar + "audit-" + server.getServerSymbolicName().getSymbolicName();
      Files.createDirectories(Paths.get(auditPath));
      options.add(auditPath);
    }

    if (server.getAuthc() != null) {
      options.add("-z");
      options.add(server.getAuthc());
    }

    if (server.getSecurityDir() != null) {
      options.add("-x");
      Path securityRootDirectoryPath = workingDir.toPath().resolve("security-root-directory-" + server.getServerSymbolicName().getSymbolicName());
      server.getSecurityDir().createSecurityRootDirectory(securityRootDirectoryPath);
      options.add(securityRootDirectoryPath.toString());
    }

    if (server.isSslTls()) {
      options.add("-t");
      options.add("true");
    }

    if (server.isWhitelist()) {
      options.add("-w");
      options.add("true");
    }

    if (server.getProperties() != null) {
      options.add("-T");
      options.add(server.getProperties());
    }

    if (server.getClusterName() != null) {
      options.add("-N");
      options.add(server.getClusterName());
    }

    LOGGER.debug("Server startup options: {}", options);
    return options;
  }

  private String getTsaCreateExecutable(File kitLocation) {
    String execPath = "server" + separator + "bin" + separator + "start-tc-server" + OS.INSTANCE.getShellExtension();
    if (distribution.getPackageType() == PackageType.KIT) {
      return kitLocation.getAbsolutePath() + separator + execPath;
    } else if (distribution.getPackageType() == PackageType.SAG_INSTALLER) {
      return kitLocation.getAbsolutePath() + separator + terracottaInstallationRoot() + separator + execPath;
    }
    throw new IllegalStateException("Can not define Terracotta server start command for distribution: " + distribution);
  }

  private ToolExecutionResult executeCommand(List<String> command, TerracottaCommandLineEnvironment env,
                                             File workingDir, Map<String, String> envOverrides) {
    try {
      LOGGER.debug("Config tool command: {}", command);
      ProcessResult processResult = new ProcessExecutor(command)
          .directory(workingDir)
          .environment(env.buildEnv(envOverrides))
          .readOutput(true)
          .redirectOutputAlsoTo(Slf4jStream.of(ExternalLoggers.configToolLogger).asInfo())
          .redirectErrorStream(true)
          .execute();
      return new ToolExecutionResult(processResult.getExitValue(), processResult.getOutput().getLines());
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  List<String> createConfigToolCommand(File installLocation, File workingDir, SecurityRootDirectory securityDir, String[] arguments) {
    List<String> command = new ArrayList<>();
    command.add(getConfigToolExecutable(installLocation));
    if (securityDir != null) {
      Path securityRootDirectoryPath = workingDir.toPath().resolve("config-tool-security-dir");
      securityDir.createSecurityRootDirectory(securityRootDirectoryPath);
      command.add("-srd");
      command.add(securityRootDirectoryPath.toString());
    }
    command.addAll(Arrays.asList(arguments));
    return command;
  }

  List<String> createActivateCommand(File installLocation, File workingDir, License license, SecurityRootDirectory securityDir, String[] arguments) {
    List<String> command = createConfigToolCommand(installLocation, workingDir, securityDir, arguments);
    if (license != null) {
      Path licensePath = workingDir.toPath().resolve(license.getFilename());
      command.add("-l");
      command.add(licensePath.toString());
    }
    return command;
  }

  List<String> createClusterToolCommand(File installLocation, File workingDir, SecurityRootDirectory securityDir, String[] arguments) {
    List<String> command = new ArrayList<>();
    command.add(getClusterToolExecutable(installLocation));
    if (securityDir != null) {
      Path securityDirPath = workingDir.toPath().resolve("cluster-tool-security-dir");
      securityDir.createSecurityRootDirectory(securityDirPath);
      command.add("-srd");
      command.add(securityDirPath.toString());
    }
    command.addAll(Arrays.asList(arguments));
    LOGGER.debug("Cluster tool command: {}", command);
    return command;
  }

  private String getConfigToolExecutable(File installLocation) {
    String execPath = "tools" + separator + "bin" + separator + "config-tool" + OS.INSTANCE.getShellExtension();

    if (distribution.getPackageType() == PackageType.KIT) {
      return installLocation.getAbsolutePath() + separator + execPath;
    } else if (distribution.getPackageType() == PackageType.SAG_INSTALLER) {
      return installLocation.getAbsolutePath() + separator + terracottaInstallationRoot() + separator + execPath;
    }
    throw new IllegalStateException("Can not define config tool command for distribution: " + distribution);
  }

  private String getClusterToolExecutable(File installLocation) {
    String execPath = "tools" + separator + "bin" + separator + "cluster-tool" + OS.INSTANCE.getShellExtension();

    if (distribution.getPackageType() == PackageType.KIT) {
      return installLocation.getAbsolutePath() + separator + execPath;
    } else if (distribution.getPackageType() == PackageType.SAG_INSTALLER) {
      return installLocation.getAbsolutePath() + separator + terracottaInstallationRoot() + separator + execPath;
    }
    throw new IllegalStateException("Can not define cluster tool command for distribution: " + distribution);
  }

  List<String> startTmsCommand(File installLocation) {
    List<String> command = new ArrayList<>();
    command.add(getStartTmsExecutable(installLocation));
    LOGGER.debug("Start TMS command: {}", command);
    return command;
  }

  private String getStartTmsExecutable(File installLocation) {
    String execPath = "tools" + separator + "management" + separator + "bin" + separator + "start" + OS.INSTANCE.getShellExtension();
    if (distribution.getPackageType() == PackageType.KIT) {
      return installLocation.getAbsolutePath() + separator + execPath;
    } else if (distribution.getPackageType() == PackageType.SAG_INSTALLER) {
      return installLocation.getAbsolutePath() + separator + terracottaInstallationRoot() + separator + execPath;
    }
    throw new IllegalStateException("Can not define TMS start command for distribution: " + distribution);
  }

  List<String> startVoterCommand(File installLocation, File workingDir, SecurityRootDirectory securityRootDirectory, TerracottaVoter terracottaVoter) {
    List<String> command = new ArrayList<>();
    command.add(getStartVoterExecutable(installLocation));
    if (securityRootDirectory != null) {
      Path securityDirPath = workingDir.toPath().resolve("voter-security-dir");
      securityRootDirectory.createSecurityRootDirectory(securityDirPath);
      command.add("-srd");
      command.add(securityDirPath.toString());
    }
    command.add("-s");
    command.add(join(",", terracottaVoter.getHostPorts()));
    LOGGER.debug("Start voter command: {}", command);
    return command;
  }

  private String getStartVoterExecutable(File installLocation) {
    String execPath = "tools" + separator + "voter" + separator + "bin" + separator + "start-tc-voter" + OS.INSTANCE.getShellExtension();
    if (distribution.getPackageType() == PackageType.KIT) {
      return installLocation.getAbsolutePath() + separator + execPath;
    } else if (distribution.getPackageType() == PackageType.SAG_INSTALLER) {
      return installLocation.getAbsolutePath() + separator + terracottaInstallationRoot() + separator + execPath;
    }
    throw new IllegalStateException("Can not define voter start command for distribution: " + distribution);
  }

  @Override
  public String terracottaInstallationRoot() {
    return "TerracottaDB";
  }

  @Override
  public void prepareTMS(File kitDir, File workingDir, TmsServerSecurityConfig tmsServerSecurityConfig) {
    File tmcPropertiesInput = new File(kitDir, "tools/management/conf/tmc.properties");
    File tmcPropertiesOutput;
    if (AngelaProperties.KIT_COPY.getBooleanValue()) {
      tmcPropertiesOutput = new File(workingDir, "tools/management/conf/tmc.properties");
    } else {
      tmcPropertiesOutput = new File(workingDir, "conf/tmc.properties");
    }
    prepareTMS(tmcPropertiesInput, tmcPropertiesOutput, tmsServerSecurityConfig, workingDir);
  }
}
