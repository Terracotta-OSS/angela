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
package org.terracotta.angela.agent.client;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terracotta.angela.agent.Agent;
import org.terracotta.angela.agent.com.AgentGroup;
import org.terracotta.angela.agent.com.AgentID;
import org.terracotta.angela.agent.com.Exceptions;
import org.terracotta.angela.common.TerracottaCommandLineEnvironment;
import org.terracotta.angela.common.topology.InstanceId;
import org.terracotta.angela.common.util.ExternalLoggers;
import org.terracotta.angela.common.util.LogOutputStream;
import org.terracotta.angela.common.util.OS;
import org.zeroturnaround.exec.ProcessExecutor;
import org.zeroturnaround.exec.StartedProcess;
import org.zeroturnaround.process.PidUtil;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.terracotta.angela.common.AngelaProperties.ROOT_DIR;

/**
 * @author Aurelien Broszniowski
 */

public class RemoteClientManager {

  private final static Logger logger = LoggerFactory.getLogger(RemoteClientManager.class);

  private static final String CLASSPATH_SUBDIR_NAME = "lib";

  private final Path kitInstallationPath;
  private final InstanceId instanceId;

  public RemoteClientManager(InstanceId instanceId) {
    this.kitInstallationPath = Agent.WORK_DIR.resolve(instanceId.toString());
    this.instanceId = instanceId;
  }

  public Path getClientInstallationPath() {
    return kitInstallationPath;
  }

  public Path getClientClasspathRoot() {
    return kitInstallationPath.resolve(CLASSPATH_SUBDIR_NAME);
  }

  @SuppressWarnings("BusyWait")
  @SuppressFBWarnings("REC_CATCH_EXCEPTION")
  public AgentID spawnClient(TerracottaCommandLineEnvironment tcEnv, AgentGroup group) {
    try {
      // tcEnv comes from the main agent through ignite serialization (from the client array config).
      // Its content will either be what the user has configured for the client array or the default tcEnv used in the main agent.
      // If the main agent is using resolver=user, then the JVM used to spawn the current agent will be used.
      // If the main agent is suing resolver=toolchain, then the JVM used will be the one matching the version/vendor criteria in teh toolchain
      // If the user has overridden the env to use for this client, then it will be either one or the other option above.
      Path javaHome = tcEnv.getJavaHome();

      final AtomicBoolean started = new AtomicBoolean(false);
      AtomicReference<AgentID> agentID = new AtomicReference<>();
      List<String> cmdLine = new ArrayList<>();
      if (OS.INSTANCE.isWindows()) {
        cmdLine.add(javaHome + "\\bin\\java.exe");
      } else {
        cmdLine.add(javaHome + "/bin/java");
      }
      cmdLine.add("-classpath");
      cmdLine.add(buildClasspath());
      if (!tcEnv.getJavaOpts().isEmpty()) {
        cmdLine.addAll(tcEnv.getJavaOpts());
      }
      // angela.java.resolver=user will ensure that any usage of TerracottaCommandLineEnvironment
      // will point to the exact same JVM as the one used to start the process by default
      cmdLine.add("-Dangela.java.resolver=user");
      cmdLine.add("-Dangela.process=spawned");
      cmdLine.add("-Dangela.directJoin=" + String.join(",", group.getPeerAddresses()));
      cmdLine.add("-Dangela.group=" + group.getId());
      cmdLine.add("-Dangela.instanceName=" + instanceId);
      cmdLine.add("-D" + ROOT_DIR.getPropertyName() + "=" + Agent.ROOT_DIR);
      cmdLine.add(Agent.class.getName());

      if (logger.isDebugEnabled()) {
        logger.info("Spawning client agent: {} with: {}", instanceId, String.join(" ", cmdLine));
      } else {
        logger.info("Spawning client agent: {}", instanceId);
      }

      ProcessExecutor processExecutor = new ProcessExecutor()
          .command(cmdLine)
          .redirectOutput(new LogOutputStream() {
            @Override
            protected void processLine(String line) {
              ExternalLoggers.clientLogger.info("[{}] {}", instanceId, line);
              if (line.startsWith(Agent.AGENT_IS_READY_MARKER_LOG)) {
                agentID.set(AgentID.valueOf(line.substring(Agent.AGENT_IS_READY_MARKER_LOG.length() + 2)));
                started.set(true);
              }
            }
          })
          .redirectErrorStream(true)
          .directory(getClientInstallationPath().toFile());
      StartedProcess startedProcess = processExecutor.start();

      logger.info("Waiting for spawned agent with PID: {} to be ready...", PidUtil.getPid(startedProcess.getProcess()));
      while (startedProcess.getProcess().isAlive() && !started.get()) {
        Thread.sleep(200); // no need to do a short wait because ignite startup is really slow
      }
      if (!startedProcess.getProcess().isAlive()) {
        throw new RuntimeException("Client process died in infancy");
      }

      AgentID id = agentID.get();
      if (id == null) {
        throw new AssertionError("No AgentID");
      }

      logger.info("Spawned client with PID {}", id.getPid());
      return id;
    } catch (IOException | InterruptedException e) {
      logger.error("Error spawning client {}", instanceId, e);
      throw Exceptions.asRuntime("Error spawning client " + instanceId, e);
    }
  }

  private String buildClasspath() throws IOException {
    final Path root = getClientClasspathRoot();
    if (!Files.isDirectory(root)) {
      throw new RuntimeException("Cannot build client classpath before the classpath root is uploaded");
    }
    StringBuilder sb = new StringBuilder();
    try (Stream<Path> list = Files.list(root)) {
      list.forEach(cpentry -> sb.append(CLASSPATH_SUBDIR_NAME).append(File.separator).append(cpentry.getFileName()).append(File.pathSeparator));
    }

    String agentClassName = Agent.class.getName().replace('.', '/');
    URL agentUrl = Agent.class.getResource("/" + agentClassName + ".class");
    if (agentUrl != null) {
      String agentPath;
      if (agentUrl.getProtocol().equals("jar")) {
        agentPath = agentUrl.getPath();
        agentPath = agentPath.substring(0, agentPath.lastIndexOf('!'));
        if (agentPath.startsWith("file:")) {
          agentPath = new File(URI.create(agentPath)).toPath().toAbsolutePath().toString();
          sb.append(agentPath);
        } else {
          logger.error("Unexpected location for Agent.class; omitting from classpath: {}", agentUrl);
        }
      } else if (agentUrl.getProtocol().equals("file")) {
        agentPath = new File(agentUrl.toString()).toPath().toAbsolutePath().toString();
        sb.append(agentPath);
      } else {
        logger.error("Unexpected location for Agent.class; omitting from classpath: {}", agentUrl);
      }
    } else {
      logger.error("Failed to determine path to Agent.class; omitting from classpath");
    }

    return sb.toString();
  }

}
