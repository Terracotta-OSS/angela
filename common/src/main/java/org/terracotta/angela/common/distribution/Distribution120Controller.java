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
import org.terracotta.angela.common.ToolExecutionResult;
import org.terracotta.angela.common.tcconfig.SecurityRootDirectory;
import org.terracotta.angela.common.topology.PackageType;
import org.terracotta.angela.common.util.ExternalLoggers;
import org.terracotta.angela.common.util.OS;
import org.zeroturnaround.exec.ProcessExecutor;
import org.zeroturnaround.exec.ProcessResult;
import org.zeroturnaround.exec.stream.slf4j.Slf4jStream;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static java.io.File.separator;

/**
 * @author Aurelien Broszniowski
 */

public class Distribution120Controller extends Distribution107Controller{
  private final static Logger LOGGER = LoggerFactory.getLogger(Distribution120Controller.class);

  public Distribution120Controller(Distribution distribution) {
    super(distribution);
  }

  @Override
  public ToolExecutionResult invokeImportTool(File kitDir, File workingDir, SecurityRootDirectory securityDir,
                                              TerracottaCommandLineEnvironment env, Map<String, String> envOverrides, String... arguments) {
    try {
      ProcessResult processResult = new ProcessExecutor(createImportToolCommand(kitDir, workingDir, securityDir, arguments))
              .directory(workingDir)
              .environment(env.buildEnv(envOverrides))
              .readOutput(true)
              .redirectOutputAlsoTo(Slf4jStream.of(ExternalLoggers.importToolLogger).asInfo())
              .redirectErrorStream(true)
              .execute();
      return new ToolExecutionResult(processResult.getExitValue(), processResult.getOutput().getLines());
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  List<String> createImportToolCommand(File installLocation, File workingDir, SecurityRootDirectory securityDir, String[] arguments) {
    List<String> command = new ArrayList<>();
    command.add(getImportToolExecutable(installLocation));
    if (securityDir != null) {
      Path securityDirPath = workingDir.toPath().resolve("import-tool-security-dir");
      securityDir.createSecurityRootDirectory(securityDirPath);
      command.add("-srd");
      command.add(securityDirPath.toString());
    }
    command.addAll(Arrays.asList(arguments));
    LOGGER.debug("Import tool command: {}", command);
    return command;
  }

  private String getImportToolExecutable(File installLocation) {
    String execPath = "tools" + separator + "bin" + separator + "import-tool" + OS.INSTANCE.getShellExtension();

    if (distribution.getPackageType() == PackageType.KIT) {
      return installLocation.getAbsolutePath() + separator + execPath;
    } else if (distribution.getPackageType() == PackageType.SAG_INSTALLER) {
      return installLocation.getAbsolutePath() + separator + terracottaInstallationRoot() + separator + execPath;
    }
    throw new IllegalStateException("Can not define import tool command for distribution: " + distribution);
  }


}
