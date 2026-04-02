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
package org.terracotta.angela.common.distribution;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terracotta.angela.common.TerracottaCommandLineEnvironment;
import org.terracotta.angela.common.TerracottaServerHandle;
import org.terracotta.angela.common.TerracottaServerState;
import org.terracotta.angela.common.tcconfig.ServerSymbolicName;
import org.terracotta.angela.common.tcconfig.TerracottaServer;
import org.terracotta.angela.common.topology.Topology;

import java.io.File;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static java.lang.String.join;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

public class Distribution107InlineController extends Distribution107Controller {
  private final static Logger LOGGER = LoggerFactory.getLogger(Distribution107InlineController.class);

  public Distribution107InlineController(Distribution distribution) {
    super(distribution);
  }

  @Override
  public TerracottaServerHandle createTsa(TerracottaServer terracottaServer, File kitDir, File workingDir,
                                          Topology topology, Map<ServerSymbolicName, Integer> proxiedPorts,
                                          TerracottaCommandLineEnvironment tcEnv, Map<String, String> envOverrides,
                                          List<String> startUpArgs, Duration inactivityKillerDelay) {
    List<String> options = startUpArgs != null && !startUpArgs.isEmpty() ? addServerHome(startUpArgs, workingDir) : addOptions(terracottaServer, workingDir);

    return createServer(kitDir.toPath(), terracottaServer.getServerSymbolicName().getSymbolicName(), workingDir.toPath(), options);
  }

  private TerracottaServerHandle createServer(Path kitDir, String serverName, Path serverWorking, List<String> cmd) {
    LOGGER.debug("Creating TSA server: {} at: {} from: {} with CLI: {}", serverName, serverWorking, kitDir, String.join(" ", cmd));
    final AtomicReference<Object> ref = new AtomicReference<>(startIsolatedServer(kitDir, serverName, serverWorking, cmd));
    final  AtomicBoolean isAlive = new AtomicBoolean(true);
    Thread t = new Thread(()->{
      try {
        while ((Boolean)invokeOnObject(ref.get(), "waitUntilShutdown")) {
          ref.set(startIsolatedServer(kitDir, serverName, serverWorking, cmd));
        }
      } catch (Throwable tt) {
        ref.set(null);
        LOGGER.error("restart failed", tt);
      }
      isAlive.set(false);
    });
    t.setDaemon(true);
    t.start();

    return new TerracottaServerHandle() {

      @Override
      public TerracottaServerState getState() {
        if (isAlive()) {
          String state = invokeOnServerMBean("Server", "getState",  null);
          switch (state) {
            case "DIAGNOSTIC":
              return TerracottaServerState.STARTED_IN_DIAGNOSTIC_MODE;
            case "START-STATE":
              if (Boolean.parseBoolean(invokeOnServerMBean("ConsistencyManager", "isBlocked", null))) {
                return TerracottaServerState.START_SUSPENDED;
              }
              return TerracottaServerState.STARTING;
            case "STOP-STATE":
              return TerracottaServerState.STOPPED;
            case "ACTIVE-COORDINATOR":
              if (Boolean.parseBoolean(invokeOnServerMBean("ConsistencyManager", "isBlocked", null))) {
                return TerracottaServerState.START_SUSPENDED;
              }
              if (Boolean.parseBoolean(invokeOnServerMBean("Server", "isAcceptingClients", null))) {
                return TerracottaServerState.STARTED_AS_ACTIVE;
              } else {
                return TerracottaServerState.START_SUSPENDED;
              }
            case "PASSIVE":
            case "PASSIVE-SYNCING":
            case "PASSIVE-UNINITIALIZED":
              return TerracottaServerState.STARTING;
            case "PASSIVE-STANDBY":
              if (Boolean.parseBoolean(invokeOnServerMBean("ConsistencyManager", "isBlocked", null))) {
                return TerracottaServerState.START_SUSPENDED;
              }
              return TerracottaServerState.STARTED_AS_PASSIVE;
            case "PASSIVE-RELAY":
              return TerracottaServerState.STARTED_AS_PASSIVE_RELAY;
            case "PASSIVE-REPLICA-START":
              return TerracottaServerState.STARTED_AS_PASSIVE_REPLICA_START;
            case "PASSIVE-REPLICA":
              return TerracottaServerState.STARTED_AS_PASSIVE_REPLICA;
            default:
              return (!isAlive() || isStopped()) ? TerracottaServerState.STOPPED : TerracottaServerState.STARTING;
          }
        } else {
          return TerracottaServerState.STOPPED;
        }
      }

      public boolean isStopped() {
        Object server = ref.get();
        if (server instanceof Future) {
          return ((Future)server).isDone();
        } else {
          return (Boolean)invokeOnObject(server, "isStopped");
        }
      }

      @Override
      public int getJavaPid() {
        return 0;
      }

      @Override
      public boolean isAlive() {
        return isAlive.get();
      }

      @Override
      public void stop() {
        boolean stop = true;
        while (stop) {
          stop = Boolean.parseBoolean(invokeOnServerMBean("Server", "stopAndWait", null));
        }
      }

      private String invokeOnServerMBean(String target, String call, String arg) {
        Object serverJMX = invokeOnObject(ref.get(), "getManagement");
        try {
          Method m = serverJMX.getClass().getMethod("call", String.class, String.class, String.class);
          m.setAccessible(true);
          return m.invoke(serverJMX, target, call, arg).toString();
        } catch (NoSuchMethodException |
                SecurityException |
                IllegalAccessException |
                IllegalArgumentException |
                InvocationTargetException s) {
          LOGGER.warn("unable to call", s);
          return "ERROR";
        }
      }
    };
  }

  private static Object invokeOnObject(Object server, String method, Object...args) {
    try {
      Class<?>[] clazz = new Class<?>[args.length];
      for (int x=0;x<args.length;x++) {
        Class<?> sig = args[x] != null ? args[x].getClass() : null;
        clazz[x] = sig;
      }
      Method m = server.getClass().getMethod(method, clazz);
      m.setAccessible(true);
      return m.invoke(server, args);
    } catch (Exception s) {
      LOGGER.warn("unable to invoke", s);
      return "ERROR";
    }
  }

  private synchronized Object startIsolatedServer(Path kitDir, String serverName, Path serverWorking, List<String> cmd) {
    Path tc = kitDir.resolve(Paths.get("server", "lib", "tc.jar"));
    ClassLoader oldLoader = Thread.currentThread().getContextClassLoader();
    Thread.currentThread().setContextClassLoader(null);
    try {
      URL url = tc.toUri().toURL();
      URL resource = serverWorking.toUri().toURL();
      System.setProperty("tc.install-root", kitDir.resolve("server").toString());

      ClassLoader loader = new IsolatedClassLoader(new URL[] {resource, url});
      Method m = Class.forName("com.tc.server.TCServerMain", true, loader).getMethod("createServer", List.class, OutputStream.class);
      return m.invoke(null, cmd, Files.newOutputStream(serverWorking.resolve("stdout.txt"), StandardOpenOption.CREATE, StandardOpenOption.APPEND));
    } catch (RuntimeException mal) {
      throw mal;
    } catch (Exception e) {
      throw new RuntimeException(e);
    } finally {
      Thread.currentThread().setContextClassLoader(oldLoader);
    }
  }

  private List<String> addServerHome(List<String> options, File workingDir) {
    ArrayList<String> args = new ArrayList<>(options.size() + 2);
    // --server-home is compatible with both old and new options
    args.add(ServerOption.NODE_HOME_DIR.getDeprecatedOption());
    args.add(workingDir.toString());
    args.addAll(options);
    return Collections.unmodifiableList(args);
  }

  private List<String> addOptions(TerracottaServer server, File workingDir) {
    List<String> options = new ArrayList<>();
    Path working = workingDir.toPath();

    options.add(ServerOption.NODE_HOME_DIR.getOption());
    options.add(working.toString());

    if (server.getConfigFile() != null) {
      options.add(ServerOption.CONFIG_FILE.getOption());
      options.add(server.getConfigFile());
    } else {
      // Add server name only if config file option wasn't provided
      options.add(ServerOption.NODE_NAME.getOption());
      options.add(server.getServerSymbolicName().getSymbolicName());
    }

    // Add hostname
    options.add(ServerOption.NODE_HOSTNAME.getOption());
    options.add(server.getHostName());

    if (server.getTsaPort() != 0) {
      options.add(ServerOption.NODE_PORT.getOption());
      options.add(String.valueOf(server.getTsaPort()));
    }

    if (server.getTsaGroupPort() != 0) {
      options.add(ServerOption.NODE_GROUP_PORT.getOption());
      options.add(String.valueOf(server.getTsaGroupPort()));
    }

    if (server.getBindAddress() != null) {
      options.add(ServerOption.NODE_BIND_ADDRESS.getOption());
      options.add(server.getBindAddress());
    }

    if (server.getGroupBindAddress() != null) {
      options.add(ServerOption.NODE_GROUP_BIND_ADDRESS.getOption());
      options.add(server.getGroupBindAddress());
    }

    if (server.getConfigRepo() != null) {
      options.add(ServerOption.NODE_CONFIG_DIR.getOption());
      options.add(server.getConfigRepo());
    }

    if (server.getMetaData() != null) {
      options.add(ServerOption.NODE_METADATA_DIR.getOption());
      options.add(server.getMetaData());
    }

    if (server.getDataDir().size() != 0) {
      options.add(ServerOption.DATA_DIRS.getOption());
      options.add(server.getDataDir().stream().collect(Collectors.joining(",")));
    }

    if (server.getOffheap().size() != 0) {
      options.add(ServerOption.OFFHEAP_RESOURCES.getOption());
      options.add(join(",", server.getOffheap()));
    }

    if (server.getLogs() != null) {
      options.add(ServerOption.NODE_LOG_DIR.getOption());
      options.add(server.getLogs());
    }

    if (server.getFailoverPriority() != null) {
      options.add(ServerOption.FAILOVER_PRIORITY.getOption());
      options.add(server.getFailoverPriority());
    }

    if (server.getClientLeaseDuration() != null) {
      options.add(ServerOption.CLIENT_LEASE_DURATION.getOption());
      options.add(server.getClientLeaseDuration());
    }

    if (server.getClientReconnectWindow() != null) {
      options.add(ServerOption.CLIENT_RECONNECT_WINDOW.getOption());
      options.add(server.getClientReconnectWindow());
    }

    if (server.getBackupDir() != null) {
      options.add(ServerOption.NODE_BACKUP_DIR.getOption());
      options.add(server.getBackupDir());
    }

    if (server.getAuditLogDir() != null) {
      options.add(ServerOption.SECURITY_AUDIT_LOG_DIR.getOption());
      options.add(server.getAuditLogDir());
    }

    if (server.getAuthc() != null) {
      options.add(ServerOption.SECURITY_AUTHC.getOption());
      options.add(server.getAuthc());
    }

    if (server.getSecurityDir() != null) {
      options.add(ServerOption.SECURITY_DIR.getOption());
      Path securityRootDirectoryPath = workingDir.toPath().resolve("security-root-directory-" + server.getServerSymbolicName().getSymbolicName());
      server.getSecurityDir().createSecurityRootDirectory(securityRootDirectoryPath);
      options.add(securityRootDirectoryPath.toString());
    }

    if (server.isSslTls()) {
      options.add(ServerOption.SECURITY_SSL_TLS.getOption());
      options.add("true");
    }

    if (server.isWhitelist()) {
      options.add(ServerOption.SECURITY_WHITELIST.getOption());
      options.add("true");
    }

    if (server.getProperties() != null) {
      options.add(ServerOption.TC_PROPERTIES.getOption());
      options.add(server.getProperties());
    }

    if (server.getClusterName() != null) {
      options.add(ServerOption.CLUSTER_NAME.getOption());
      options.add(server.getClusterName());
    }

    options.addAll(getRelayReplicaOptions(server));

    LOGGER.debug("Server startup options: {}", options);
    return options;
  }

}
