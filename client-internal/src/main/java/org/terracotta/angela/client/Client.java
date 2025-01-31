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
import org.apache.ignite.lang.IgniteRunnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terracotta.angela.agent.AgentController;
import org.terracotta.angela.agent.com.AgentGroup;
import org.terracotta.angela.agent.com.AgentID;
import org.terracotta.angela.agent.com.Executor;
import org.terracotta.angela.agent.com.IgniteFutureAdapter;
import org.terracotta.angela.agent.kit.LocalKitManager;
import org.terracotta.angela.client.config.ClientArrayConfigurationContext;
import org.terracotta.angela.client.filesystem.RemoteFolder;
import org.terracotta.angela.common.TerracottaCommandLineEnvironment;
import org.terracotta.angela.common.clientconfig.ClientId;
import org.terracotta.angela.common.cluster.Cluster;
import org.terracotta.angela.common.topology.InstanceId;

import java.io.Closeable;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;

import static java.util.stream.Collectors.toList;
import static org.terracotta.angela.common.AngelaProperties.KIT_INSTALLATION_DIR;
import static org.terracotta.angela.common.AngelaProperties.KIT_INSTALLATION_PATH;
import static org.terracotta.angela.common.AngelaProperties.OFFLINE;
import static org.terracotta.angela.common.AngelaProperties.SKIP_UNINSTALL;
import static org.terracotta.angela.common.AngelaProperties.getEitherOf;
import static org.terracotta.angela.common.util.JavaBinaries.javaHome;
import static org.terracotta.angela.common.util.JavaBinaries.jdkHome;

/**
 * @author Ludovic Orban
 */
public class Client implements Closeable {

  private final static Logger logger = LoggerFactory.getLogger(Client.class);

  private final AgentID clientAgentID;
  private final AgentID parentAgentID;
  private final InstanceId instanceId;
  private final ClientId clientId;
  private final transient Executor executor;
  private boolean stopped = false;
  private boolean closed = false;

  private Client(Executor executor, InstanceId instanceId, ClientId clientId, AgentID clientAgentID, AgentID parentAgentID) {
    this.instanceId = instanceId;
    this.clientId = clientId;
    this.executor = executor;
    this.clientAgentID = clientAgentID;
    this.parentAgentID = parentAgentID;
  }

  public AgentID getClientAgentID() {
    return clientAgentID;
  }

  public ClientId getClientId() {
    return clientId;
  }

  int getPid() {
    return getClientAgentID().getPid();
  }

  public static Client spawn(Executor executor, InstanceId instanceId, ClientId clientId, ClientArrayConfigurationContext clientArrayConfigurationContext, LocalKitManager localKitManager, TerracottaCommandLineEnvironment tcEnv) {
    final AgentID parentAgentID = executor.getAgentID(clientId.getHostName());
    logger.info("Spawning client: {} instance: {} through agent: {}; using environment: {}", clientId, instanceId, parentAgentID, tcEnv);
  
    String kitInstallationPath = getEitherOf(KIT_INSTALLATION_DIR, KIT_INSTALLATION_PATH);
    localKitManager.setupLocalInstall(clientArrayConfigurationContext.getLicense(), kitInstallationPath, OFFLINE.getBooleanValue(), tcEnv);

    try {
      final List<Path> jars = listClasspathFiles(localKitManager);
      for (Path jar : jars) {
        logger.debug("Uploading classpath file : {}", jar.getFileName());
      }
      executor.uploadClientJars(parentAgentID, instanceId, jars);
      AgentGroup group = executor.getGroup();
      AgentID clientAgentID = executor.execute(parentAgentID, () -> AgentController.getInstance().spawnClient(instanceId, tcEnv, group));
      logger.info("Started client: {} instance: {} through agent: {} on agent: {}", clientId, instanceId, parentAgentID, clientAgentID);

      return new Client(executor, instanceId, clientId, clientAgentID, parentAgentID);
    } catch (Exception e) {
      logger.error("Cannot create client: {} through: {}: {}", instanceId, parentAgentID, e.getMessage(), e);
      throw new RuntimeException(e);
    }
  }

  private static List<Path> listClasspathFiles(LocalKitManager localKitManager) {
    List<File> files = new ArrayList<>();

    String javaHome = jdkHome().orElse(javaHome()).toString();
    logger.debug("Skipping all JVM libraries inside {}", javaHome);

    String[] classpathJarNames = System.getProperty("java.class.path").split(File.pathSeparator);
    boolean substituteClientJars = localKitManager.getDistribution() != null;
    List<File> libs = new ArrayList<>();

    for (String classpathJarName : classpathJarNames) {
      if (classpathJarName.startsWith(javaHome)) {
        logger.debug("Skipping {} as it is part of the JVM", classpathJarName);
        continue; // part of the JVM, skip it
      }
      File classpathFile = new File(classpathJarName);

      File equivalentClientJar = localKitManager.equivalentClientJar(classpathFile);
      if (substituteClientJars && equivalentClientJar != null) {
        logger.debug("Skipping upload of classpath file as kit contains equivalent jar in client libs : {}", classpathFile.getName());
        libs.add(equivalentClientJar);
        continue;
      }

      files.add(classpathFile);
    }

    if (substituteClientJars) {
      logger.debug("Enhancing client classpath with client jars of {}", localKitManager.getDistribution());
      files.addAll(libs);
      logger.debug("Adding clients jars : {}", libs);
    }

    return files.stream().map(File::toPath).collect(toList());
  }

  Future<Void> submit(ClientId clientId, ClientJob clientJob) {
    Cluster cluster = executor.getCluster(clientId);
    IgniteCallable<Void> call = () -> {
      try {
        clientJob.run(cluster);
        return null;
      } catch (Throwable t) {
        logger.error("clientJob failed", t);
        throw new IgniteFutureAdapter.RemoteExecutionException("Remote ClientJob failed", exceptionToString(t));
      }
    };
    return executor.executeAsync(clientAgentID, call);
  }

  private static String exceptionToString(Throwable t) {
    StringWriter sw = new StringWriter();
    PrintWriter pw = new PrintWriter(sw);
    t.printStackTrace(pw);
    pw.close();
    return sw.toString();
  }

  public RemoteFolder browse(String root) {
    return new RemoteFolder(executor.forAgent(clientAgentID), null, root);
  }

  public InstanceId getInstanceId() {
    return instanceId;
  }

  public String getHostName() {
    return getClientId().getHostName();
  }

  public String getSymbolicName() {
    return getClientId().getSymbolicName().getSymbolicName();
  }

  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;

    stop();
    if (!SKIP_UNINSTALL.getBooleanValue()) {
      logger.debug("Wiping up data for client: {} instance: {} started from: {}", clientId, instanceId, parentAgentID);
      try {
        executor.execute(parentAgentID, (IgniteRunnable) () -> AgentController.getInstance().deleteClient(instanceId));
      } catch (Throwable e) {
        logger.error("Error wiping data for client {} instance {}", clientId, instanceId, e);
        if (e instanceof Error) {
          throw e;
        }
      }
    }
  }

  public void stop() {
    if (stopped) {
      return;
    }
    stopped = true;

    logger.info("Killing agent: {} for client:{} instance: {} started from: {}", clientAgentID, clientId, instanceId, parentAgentID);
    final int pid = clientAgentID.getPid();
    try {
      executor.execute(parentAgentID, (IgniteRunnable) () -> AgentController.getInstance().stopClient(instanceId, pid));
      executor.getGroup().getAllAgents().remove(clientAgentID);   // Dead agent -- remove
    } catch (Throwable e) {
      logger.error("Error killing agent {} for client {}", clientAgentID, clientId, e);
      throw e;
    }
  }
}
