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
package org.terracotta.angela.common.util;

import org.terracotta.angela.common.TerracottaCommandLineEnvironment;
import org.terracotta.angela.common.ToolExecutionResult;
import org.zeroturnaround.exec.ProcessExecutor;
import org.zeroturnaround.exec.ProcessResult;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class Jcmd {
  public static ToolExecutionResult jcmd(long javaPid, TerracottaCommandLineEnvironment tcEnv, String... arguments) {
    Path javaHome = tcEnv.getJavaHome();
    Path path = JavaBinaries.find("jcmd", javaHome).orElseThrow(() -> new IllegalStateException("jcmd not found"));
    List<String> cmdLine = new ArrayList<>();
    cmdLine.add(path.toAbsolutePath().toString());
    cmdLine.add(Long.toString(javaPid));
    cmdLine.addAll(Arrays.asList(arguments));

    try {
      ProcessResult processResult = new ProcessExecutor(cmdLine)
          .readOutput(true)
          .redirectErrorStream(true)
          .execute();
      return new ToolExecutionResult(processResult.getExitValue(), processResult.getOutput().getLines());
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
