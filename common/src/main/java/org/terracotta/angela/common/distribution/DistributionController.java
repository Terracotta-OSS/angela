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

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.terracotta.angela.common.TerracottaCommandLineEnvironment;
import org.terracotta.angela.common.TerracottaManagementServerInstance;
import org.terracotta.angela.common.TerracottaServerHandle;
import org.terracotta.angela.common.TerracottaVoter;
import org.terracotta.angela.common.TerracottaVoterInstance.TerracottaVoterInstanceProcess;
import org.terracotta.angela.common.ToolExecutionResult;
import org.terracotta.angela.common.tcconfig.License;
import org.terracotta.angela.common.tcconfig.SecurityRootDirectory;
import org.terracotta.angela.common.tcconfig.ServerSymbolicName;
import org.terracotta.angela.common.tcconfig.TerracottaServer;
import org.terracotta.angela.common.tms.security.config.TmsServerSecurityConfig;
import org.terracotta.angela.common.topology.Topology;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static java.util.Collections.emptyMap;

/**
 * @author Aurelien Broszniowski
 */
public abstract class DistributionController {

  protected final Distribution distribution;

  DistributionController(Distribution distribution) {
    this.distribution = distribution;
  }

  public abstract TerracottaServerHandle createTsa(TerracottaServer terracottaServer, File kitDir, File workingDir, Topology topology, Map<ServerSymbolicName, Integer> proxiedPorts, TerracottaCommandLineEnvironment tcEnv, Map<String, String> envOverrides, List<String> startUpArgs, Duration inactivityKillerDelay);

  public abstract TerracottaManagementServerInstance.TerracottaManagementServerInstanceProcess startTms(File kitDir, File workingDir, TerracottaCommandLineEnvironment env, Map<String, String> envOverrides);

  public abstract void stopTms(File installLocation, TerracottaManagementServerInstance.TerracottaManagementServerInstanceProcess terracottaServerInstanceProcess, TerracottaCommandLineEnvironment tcEnv);

  public abstract TerracottaVoterInstanceProcess startVoter(TerracottaVoter terracottaVoter, File kitDir, File workingDir,
                                                            SecurityRootDirectory securityDir, TerracottaCommandLineEnvironment tcEnv, Map<String, String> envOverrides);

  public abstract void stopVoter(TerracottaVoterInstanceProcess terracottaVoterInstanceProcess);

  public abstract ToolExecutionResult invokeClusterTool(File kitDir, File workingDir, SecurityRootDirectory securityDir,
                                                        TerracottaCommandLineEnvironment env, Map<String, String> envOverrides, String... arguments);

  public abstract ToolExecutionResult configureCluster(File kitDir, File workingDir, Topology topology, Map<ServerSymbolicName, Integer> proxyTsaPorts, License license, SecurityRootDirectory securityDir,
                                                       TerracottaCommandLineEnvironment env, Map<String, String> envOverrides, String... arguments);

  public abstract ToolExecutionResult invokeConfigTool(File kitDir, File workingDir, SecurityRootDirectory securityDir,
                                                       TerracottaCommandLineEnvironment env, Map<String, String> envOverrides, String... arguments);

  public abstract ToolExecutionResult activateCluster(File kitDir, File workingDir, License license, SecurityRootDirectory securityDir,
                                                      TerracottaCommandLineEnvironment env, Map<String, String> envOverrides, String... arguments);

  public abstract URI tsaUri(Collection<TerracottaServer> servers, Map<ServerSymbolicName, Integer> proxyTsaPorts);

  public abstract String clientJarsRootFolderName(Distribution distribution);

  public abstract String pluginJarsRootFolderName(Distribution distribution);

  public abstract String terracottaInstallationRoot();

  public abstract void prepareTMS(File kitDir, File workingDir, TmsServerSecurityConfig tmsServerSecurityConfig);

  @SuppressFBWarnings("RV_RETURN_VALUE_IGNORED_BAD_PRACTICE")
  @SuppressWarnings("ResultOfMethodCallIgnored")
  protected void prepareTMS(Properties properties, File tmcPropertiesOutput, TmsServerSecurityConfig tmsServerSecurityConfig, File workDir) {
    tmcPropertiesOutput.getParentFile().mkdirs();

    Map<String, String> props = tmsServerSecurityConfig == null ? emptyMap() : tmsServerSecurityConfig.toMap();

    String auditDir = props.get(TmsServerSecurityConfig.AUDIT_DIRECTORY);
    if (auditDir != null && !auditDir.isEmpty()) {
      File path = new File(auditDir);
      if (!path.isAbsolute()) {
        path = new File(workDir, path.getPath());
      }
      path.mkdirs();
    }

    props.forEach((key, value) -> {
      if (value == null) {
        properties.remove(key);
      } else {
        properties.put(key, value);
      }
    });

    try (OutputStream outputStream = new FileOutputStream(tmcPropertiesOutput)) {
      properties.store(outputStream, null);
    } catch (Exception ex) {
      throw new RuntimeException("Unable to enable security in TMS tmc.properties file", ex);
    }
  }
}
