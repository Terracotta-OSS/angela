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
import org.terracotta.angela.common.topology.Topology;
import org.terracotta.angela.common.util.JavaBinaries;
import org.zeroturnaround.exec.ProcessExecutor;
import org.zeroturnaround.exec.ProcessResult;

import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * @author Aurelien Broszniowski
 */
public abstract class DistributionController {

  protected final Distribution distribution;

  DistributionController(Distribution distribution) {
    this.distribution = distribution;
  }

  public static ToolExecutionResult invokeJcmd(TerracottaServerHandle terracottaServerInstanceProcess, TerracottaCommandLineEnvironment tcEnv, String... arguments) {
    int javaPid = terracottaServerInstanceProcess.getJavaPid();
    Path javaHome = tcEnv.getJavaHome();
    Path path = JavaBinaries.find("jcmd", javaHome).orElseThrow(() -> new IllegalStateException("jcmd not found"));
    List<String> cmdLine = new ArrayList<>();
    cmdLine.add(path.toAbsolutePath().toString());
    cmdLine.add(String.valueOf(javaPid));
    cmdLine.addAll(Arrays.asList(arguments));

    try {
      ProcessResult processResult = new ProcessExecutor(cmdLine)
          .redirectErrorStream(true)
          .readOutput(true)
          .execute();
      return new ToolExecutionResult(processResult.getExitValue(), processResult.getOutput().getLines());
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public abstract TerracottaServerHandle createTsa(TerracottaServer terracottaServer, File kitDir, File workingDir, Topology topology, Map<ServerSymbolicName, Integer> proxiedPorts, TerracottaCommandLineEnvironment tcEnv, Map<String, String> envOverrides, List<String> startUpArgs);

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
}
