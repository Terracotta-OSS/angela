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
package org.terracotta.angela.agent.com.grid.hazelcast;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.common.StreamCopier;
import net.schmizz.sshj.connection.ConnectionException;
import net.schmizz.sshj.connection.channel.direct.PTYMode;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.transport.TransportException;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import net.schmizz.sshj.xfer.InMemoryDestFile;
import net.schmizz.sshj.xfer.scp.SCPRemoteException;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terracotta.angela.agent.Agent;
import org.terracotta.angela.agent.com.AgentID;
import org.terracotta.angela.agent.com.Exceptions;
import org.terracotta.angela.common.AngelaProperties;
import org.terracotta.angela.common.TerracottaCommandLineEnvironment;
import org.terracotta.angela.common.util.AngelaVersions;
import org.terracotta.angela.common.util.ExternalLoggers;
import org.terracotta.angela.common.util.IpUtils;
import org.terracotta.angela.common.util.JDK;
import org.terracotta.angela.common.util.JavaLocationResolver;
import org.terracotta.angela.common.util.LogOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static java.util.stream.Collectors.joining;
import static org.terracotta.angela.common.AngelaProperties.ROOT_DIR;
import static org.terracotta.angela.common.AngelaProperties.SSH_PORT;
import static org.terracotta.angela.common.AngelaProperties.SSH_STRICT_HOST_CHECKING;
import static org.terracotta.angela.common.AngelaProperties.SSH_USERNAME;
import static org.terracotta.angela.common.AngelaProperties.SSH_USERNAME_KEY_PATH;
import static org.terracotta.angela.common.AngelaProperties.getEitherOf;

/**
 * Executor that deploys a remote Hazelcast agent over SSH.
 * The SSH deployment logic mirrors {@code IgniteSshRemoteExecutor}; the only
 * difference is that the remote command includes {@code -Dangela.gridProvider=hazelcast}
 * so that {@link Agent#main} starts a Hazelcast member instead of an Ignite node.
 */
public class HazelcastSshRemoteExecutor extends HazelcastExecutor {

  private static final Logger logger = LoggerFactory.getLogger(HazelcastSshRemoteExecutor.class);
  private static final int MAX_LINE_LENGTH = 1024;

  private transient final Map<String, RemoteAgentHolder> clients = new HashMap<>();
  private transient String remoteUserName = SSH_USERNAME.getValue();
  private transient String remoteUserNameKeyPath = SSH_USERNAME_KEY_PATH.getValue();
  private transient TerracottaCommandLineEnvironment tcEnv = TerracottaCommandLineEnvironment.DEFAULT;
  private transient Path agentJarFile;
  private transient boolean agentJarFileShouldBeRemoved;
  private transient int port = Integer.parseInt(SSH_PORT.getValue());
  private transient boolean strictHostKeyChecking = SSH_STRICT_HOST_CHECKING.getBooleanValue();

  private static class RemoteAgentHolder implements AutoCloseable {
    RemoteAgentHolder(String hostname, SSHClient sshClient, Session session, Session.Command command) {
      this.hostname = hostname;
      this.sshClient = sshClient;
      this.session = session;
      this.command = command;
    }

    final String hostname;
    final SSHClient sshClient;
    final Session session;
    final Session.Command command;

    @Override
    public void close() {
      logger.info("Cleaning up SSH agent on: {}", hostname);

      // 0x03 is the character for CTRL-C -> send it to the remote PTY
      try {
        if (session.isOpen()) {
          OutputStream os = session.getOutputStream();
          os.write(0x03);
        }
      } catch (IOException e) {
        logger.debug("Error trying to closing SSH session. Maybe it is already closed ? Details: {}.", e.getMessage(), e);
      } finally {
        safeClose(hostname, command);
        safeClose(hostname, session);
        safeClose(hostname, sshClient);
      }
    }
  }

  public HazelcastSshRemoteExecutor(Agent agent) {
    super(agent.getGroupId(), agent.getAgentID(), ((HazelcastGridProvider) agent.getGridProvider()).getHazelcastInstance(),
        agent.getGridProvider().createClusterPrimitives());
  }

  public HazelcastSshRemoteExecutor(UUID group, AgentID agentID, com.hazelcast.core.HazelcastInstance hz) {
    super(group, agentID, hz);
  }

  public HazelcastSshRemoteExecutor setTcEnv(TerracottaCommandLineEnvironment tcEnv) {
    this.tcEnv = tcEnv;
    return this;
  }

  public HazelcastSshRemoteExecutor setRemoteUserName(String remoteUserName) {
    this.remoteUserName = remoteUserName;
    return this;
  }

  public HazelcastSshRemoteExecutor setRemoteUserNameKeyPath(String remoteUserNameKeyPath) {
    this.remoteUserNameKeyPath = remoteUserNameKeyPath;
    return this;
  }

  public HazelcastSshRemoteExecutor setPort(int port) {
    this.port = port;
    return this;
  }

  public HazelcastSshRemoteExecutor setStrictHostKeyChecking(boolean strictHostKeyChecking) {
    this.strictHostKeyChecking = strictHostKeyChecking;
    return this;
  }

  private void initAgentJar() {
    if (agentJarFile != null) {
      return;
    }
    Map.Entry<Path, Boolean> agentJar = findAgentJarFile();
    this.agentJarFile = agentJar.getKey();
    this.agentJarFileShouldBeRemoved = agentJar.getValue();
    if (this.agentJarFile == null) {
      throw new RuntimeException("agent JAR file not found, cannot use SSH remote agent launcher");
    }
  }

  @SuppressFBWarnings({"IS2_INCONSISTENT_SYNC", "NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE"})
  @Override
  public synchronized Optional<AgentID> startRemoteAgent(String hostname) {
    if (IpUtils.isLocal(hostname)) {
      agentGroup.joined(getLocalAgentID(), hostname);
      return Optional.empty();
    }

    if (clients.containsKey(hostname) || agentGroup.findRemoteAgentID(hostname).isPresent()) {
      return Optional.empty();
    }

    logger.info("Connecting via SSH to: {}", hostname);
    initAgentJar();

    try {
      SSHClient ssh = new SSHClient();

      if (!strictHostKeyChecking) {
        ssh.addHostKeyVerifier(new PromiscuousVerifier());
      }
      try {
        ssh.loadKnownHosts();
      } catch (IOException e) {
        logger.warn("Unable to load SSH known hosts. Maybe the file does not exist ? Error: {}", e.getMessage());
      }
      ssh.connect(hostname, port);

      if (remoteUserNameKeyPath == null) {
        ssh.authPublickey(remoteUserName);
      } else {
        ssh.authPublickey(remoteUserName, remoteUserNameKeyPath);
      }

      final String rootDir = getRemoteRootDir();
      final String baseDir;
      final String jarsDir;
      for (String path : Arrays.asList(
          rootDir,
          rootDir + "/.angela",
          baseDir = rootDir + "/.angela/" + hostname,
          jarsDir = rootDir + "/.angela/" + hostname + "/jars")) {
        exec(ssh, "mkdir -p " + path);
        exec(ssh, "chmod a+w " + path);
      }

      String dest = jarsDir + "/" + agentJarFile.getFileName();
      if (agentJarFile.getFileName().toString().endsWith("-SNAPSHOT.jar") || !exec(ssh, "[ -e " + dest + " ]").isPresent()) {
        logger.debug("Uploading agent jar: {} to: {}...", agentJarFile, hostname);
        ssh.newSCPFileTransfer().upload(agentJarFile.toString(), dest);
      }

      Session session = ssh.startSession();
      session.allocatePTY("vt100", 320, 96, 0, 0, Collections.<PTYMode, Integer>emptyMap());

      String remoteJavaHome = findJavaHomeFromRemoteToolchains(ssh);
      String command = remoteJavaHome + "/bin/java " +
          String.join(" ", tcEnv.getJavaOpts()) + " " +
          "-Dangela.process=spawned " +
          "-Dangela.java.resolver=user " +
          "-D" + AngelaProperties.GRID_PROVIDER.getPropertyName() + "=hazelcast " +
          "-Dangela.group=" + group + " " +
          "-Dangela.instanceName=" + Agent.AGENT_TYPE_REMOTE + " " +
          "-Dangela.directJoin=" + String.join(",", getGroup().getPeerAddresses()) + " " +
          "-D" + ROOT_DIR.getPropertyName() + "=" + baseDir + " " +
          "-jar " + dest;

      if (logger.isDebugEnabled()) {
        logger.debug("Starting remote Hazelcast agent on: {} with: {}", hostname, command);
      } else {
        logger.info("Starting remote Hazelcast agent on: {}", hostname);
      }

      Session.Command cmd = session.exec(command);

      SshLogOutputStream sshLogOutputStream = new SshLogOutputStream(hostname, cmd);
      new StreamCopier(cmd.getInputStream(), sshLogOutputStream, net.schmizz.sshj.common.LoggerFactory.DEFAULT).bufSize(MAX_LINE_LENGTH)
          .spawnDaemon("stdout");
      new StreamCopier(cmd.getErrorStream(), sshLogOutputStream, net.schmizz.sshj.common.LoggerFactory.DEFAULT).bufSize(MAX_LINE_LENGTH)
          .spawnDaemon("stderr");

      AgentID agentID = sshLogOutputStream.waitForStartedState();

      logger.info("Agent: {} started on: {}", agentID, hostname);
      clients.put(hostname, new RemoteAgentHolder(hostname, ssh, session, cmd));

      agentGroup.joined(agentID, hostname);

      return Optional.of(agentID);
    } catch (IOException | InterruptedException e) {
      RemoteAgentHolder holder = clients.remove(hostname);
      if (holder != null) {
        safeClose(hostname, holder);
      }
      throw Exceptions.asRuntime("Failed to launch Hazelcast agent at: " + remoteUserName + "@" + hostname + " (using SSH)", e);
    }
  }

  private static String getRemoteRootDir() {
    return Optional.ofNullable(AngelaProperties.REMOTE_ROOT_DIR.getValue())
        .orElseGet(() -> getEitherOf(AngelaProperties.ROOT_DIR, AngelaProperties.KITS_DIR))
        .replace('\\', '/');
  }

  @SuppressFBWarnings("NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE")
  private static Map.Entry<Path, Boolean> findAgentJarFile() {
    try {
      if (AngelaVersions.INSTANCE.isSnapshot()) {
        Path snapshotLocation = Paths.get(System.getProperty("user.home") + "/.m2/repository/org/terracotta/angela-agent/" +
            AngelaVersions.INSTANCE.getAngelaVersion() +
            "/angela-agent-" +
            AngelaVersions.INSTANCE.getAngelaVersion() +
            ".jar");

        if (Files.isRegularFile(snapshotLocation)) {
          logger.debug("Found agent jar at " + snapshotLocation);
          return new HashMap.SimpleEntry<>(snapshotLocation, false);
        }

        String projectBaseDir = System.getProperty("basedir");
        if (projectBaseDir != null) {
          Path agentBaseDir = Paths.get(projectBaseDir).getParent().resolve("agent");
          if (Files.isDirectory(agentBaseDir)) {
            snapshotLocation = agentBaseDir.resolve("target").resolve("angela-agent-" + AngelaVersions.INSTANCE.getAngelaVersion() + ".jar");
            if (Files.isRegularFile(snapshotLocation)) {
              logger.debug("Found agent jar at " + snapshotLocation);
              return new HashMap.SimpleEntry<>(snapshotLocation, false);
            }
          }
        }

        throw new RuntimeException("Agent SNAPSHOT jar file not found at " + snapshotLocation);

      } else {
        Path agentFile = Files.createTempDirectory("angela").resolve("angela-agent-" + AngelaVersions.INSTANCE.getAngelaVersion() + ".jar");
        String releaseUrl = "https://search.maven.org/remotecontent?filepath=org/terracotta/angela-agent/" +
            AngelaVersions.INSTANCE.getAngelaVersion() +
            "/angela-agent-" +
            AngelaVersions.INSTANCE.getAngelaVersion() +
            ".jar";
        try (InputStream jarIs = new URL(releaseUrl).openStream(); OutputStream fileOutputStream = Files.newOutputStream(agentFile)) {
          IOUtils.copy(jarIs, fileOutputStream);
        }
        logger.debug("Installed agent jar from Nexus at " + agentFile);
        return new HashMap.SimpleEntry<>(agentFile, true);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Could not get angela-agent jar: " + e.getMessage(), e);
    }
  }

  private static Optional<String> exec(SSHClient ssh, String line) throws TransportException, ConnectionException {
    try (Session session = ssh.startSession()) {
      Session.Command cmd = session.exec(line);
      try {
        cmd.join(10, TimeUnit.SECONDS);
        String stdout = IOUtils.toString(cmd.getInputStream(), StandardCharsets.UTF_8).trim();
        if (stdout.isEmpty()) {
          logger.debug("> Executing on: {}\n> {}", ssh.getRemoteHostname(), line);
        } else {
          logger.debug("> Executing on: {}\n> {}\n{}", ssh.getRemoteHostname(), line, stdout);
        }
        return cmd.getExitStatus() == 0 ? Optional.of(stdout) : Optional.empty();
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      } finally {
        cmd.close();
      }
    }
  }

  private String findJavaHomeFromRemoteToolchains(SSHClient ssh) throws IOException {
    if (!tcEnv.isToolchainBased()) {
      final Path javaHome = tcEnv.getJavaHome();
      logger.warn("Toolchain not used: will re-use the same current JVM path remotely on: {}: {}", ssh.getRemoteHostname(), javaHome);
      return javaHome.toString();

    } else {
      final ByteArrayOutputStream baos = new ByteArrayOutputStream();
      InMemoryDestFile localFile = new InMemoryDestFile() {
        @Override
        public OutputStream getOutputStream() {
          return baos;
        }
      };
      try {
        logger.debug("Downloading toolchain.xml file from: {}", ssh.getRemoteHostname());
        ssh.newSCPFileTransfer().download("$HOME/.m2/toolchains.xml", localFile);
      } catch (SCPRemoteException sre) {
        if (sre.getMessage().contains("No such file or directory")) {
          String remoteHomeDir = Stream.of(exec(ssh, "env").get().split("\n"))
              .filter(line -> line.startsWith("HOME="))
              .map(line -> line.substring(5))
              .findFirst()
              .orElseThrow(() -> new UncheckedIOException(sre));
          baos.reset();
          ssh.newSCPFileTransfer().download(remoteHomeDir + "/.m2/toolchains.xml", localFile);
        }
      }
      JavaLocationResolver javaLocationResolver = new JavaLocationResolver(new ByteArrayInputStream(baos.toByteArray()));
      List<JDK> jdks = javaLocationResolver.resolveJavaLocations(tcEnv.getJavaVersion(), tcEnv.getJavaVendors(), false);
      if (logger.isDebugEnabled()) {
        logger.debug("JDKs found on remote toolchain on: {} matching version: {} and vendors: {}\n - {}",
            ssh.getRemoteHostname(),
            tcEnv.getJavaVersion(),
            tcEnv.getJavaVendors(),
            jdks.stream().map(JDK::toString).collect(joining("\n - ")));
      }
      for (JDK jdk : jdks) {
        String remoteHome = jdk.getHome();
        if (exec(ssh, "[ -d \"" + remoteHome + "\" ]").isPresent()) {
          logger.info("Selected remote JDK on: {}: {}", ssh.getRemoteHostname(), jdk);
          return remoteHome;
        }
      }
      throw new RuntimeException("No JDK configured in remote toolchains.xml is valid; wanted : " + tcEnv + ", found : " + jdks);
    }
  }

  @SuppressFBWarnings("RV_RETURN_VALUE_IGNORED_BAD_PRACTICE")
  @Override
  public void close() {
    if (agentJarFileShouldBeRemoved) {
      try {
        org.terracotta.utilities.io.Files.delete(agentJarFile);
      } catch (IOException ignored) {
        // not a big deal if we cannot delete...
      }
    }
    UncheckedIOException uioe = null;
    for (Map.Entry<String, RemoteAgentHolder> entry : clients.entrySet()) {
      try {
        entry.getValue().close();
      } catch (UncheckedIOException e) {
        if (uioe == null) {
          uioe = e;
        } else {
          uioe.addSuppressed(e);
        }
      }
    }
    clients.clear();
    if (uioe != null) {
      throw uioe;
    }

    super.close();
  }

  private static void safeClose(String hostname, AutoCloseable closeable) {
    try {
      closeable.close();
    } catch (Exception e) {
      logger.warn("Error while cleaning up SSH agent on hostname: " + hostname, e);
    }
  }

  private static class SshLogOutputStream extends LogOutputStream {

    private final String serverName;
    private final Session.Command cmd;
    private final CountDownLatch started = new CountDownLatch(1);
    private final AtomicReference<AgentID> agentID = new AtomicReference<>();

    SshLogOutputStream(String serverName, Session.Command cmd) {
      this.serverName = serverName;
      this.cmd = cmd;
    }

    @Override
    protected void processLine(String line) {
      ExternalLoggers.sshLogger.info("[{}] {}", serverName, line);
      if (line.startsWith(Agent.AGENT_IS_READY_MARKER_LOG)) {
        agentID.set(AgentID.valueOf(line.substring(Agent.AGENT_IS_READY_MARKER_LOG.length() + 2)));
        started.countDown();
      }
    }

    public AgentID waitForStartedState() throws InterruptedException {
      if (!cmd.isOpen()) {
        throw new RuntimeException("agent refused to start");
      }
      started.await();
      return agentID.get();
    }
  }
}
