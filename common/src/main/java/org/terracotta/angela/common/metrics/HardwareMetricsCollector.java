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
package org.terracotta.angela.common.metrics;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terracotta.angela.common.util.Pids;
import org.terracotta.angela.common.util.ProcessUtil;
import org.zeroturnaround.exec.ProcessExecutor;
import org.zeroturnaround.exec.StartedProcess;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author Aurelien Broszniowski
 */

public class HardwareMetricsCollector {
  private final static Logger LOGGER = LoggerFactory.getLogger(HardwareMetricsCollector.class);
  public final static String METRICS_DIRECTORY = "metrics";

  private OutputStream outputStream;
  private final Map<HardwareMetric, StartedProcess> processes = new HashMap<>();

  @SuppressFBWarnings("RV_RETURN_VALUE_IGNORED_BAD_PRACTICE")
  public void startMonitoring(final Path installLocation, final Map<HardwareMetric, MonitoringCommand> commands) {
    LOGGER.info("Starting monitoring: {} into: {}", commands.keySet(), installLocation);
    Path statsDirectory = installLocation.resolve(METRICS_DIRECTORY);
    try {
      Files.createDirectories(statsDirectory);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }

    commands.forEach((hardwareMetric, command) -> {
      Path statsFile = statsDirectory.resolve(hardwareMetric.name().toLowerCase() + "-stats.log");
      LOGGER.debug("HardwareMetric log file: {}", statsFile.toAbsolutePath());
      try {
        outputStream = Files.newOutputStream(statsFile);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }

      ProcessExecutor pe = new ProcessExecutor()
          .environment(System.getenv())
          .command(command.getCommand())
          .directory(installLocation.toFile())
          .redirectErrorStream(true)
          .redirectOutput(outputStream);

      try {
        LOGGER.debug("Starting process: {} with env: {}", command.getCommand(), pe.getEnvironment());
        processes.put(hardwareMetric, pe.start());
      } catch (IOException e) {
        String msg = "Error executing command '" + command.getCommandName() + "': " + e.getMessage();
        LOGGER.error(msg, e);
        try {
          Files.write(statsFile, msg.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ioe) {
          // do not fail the loop!
          LOGGER.warn(msg, ioe);
        }
      }
    });
  }

  public boolean isMonitoringRunning(HardwareMetric hardwareMetric) {
    final StartedProcess process = processes.get(hardwareMetric);
    if (process == null) {
      return false; // No process was found - i.e. it failed at startup
    } else {
      return process.getProcess().isAlive();
    }
  }

  public void stopMonitoring() {
    List<Exception> exceptions = new ArrayList<>();

    processes.values().forEach(process -> {
      try {
        ProcessUtil.destroyGracefullyOrForcefullyAndWait(Pids.of(process.getProcess()));
      } catch (Exception e) {
        exceptions.add(e);
      }
    });

    processes.clear();

    if (this.outputStream != null) {
      try {
        this.outputStream.close();
      } catch (IOException e) {
        exceptions.add(e);
      }
      this.outputStream = null;
    }

    if (!exceptions.isEmpty()) {
      RuntimeException runtimeException = new RuntimeException();
      exceptions.forEach(runtimeException::addSuppressed);
      throw runtimeException;
    }
  }
}
