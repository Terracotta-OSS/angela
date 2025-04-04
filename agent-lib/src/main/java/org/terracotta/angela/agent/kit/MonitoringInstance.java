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
package org.terracotta.angela.agent.kit;

import org.terracotta.angela.common.metrics.HardwareMetric;
import org.terracotta.angela.common.metrics.HardwareMetricsCollector;
import org.terracotta.angela.common.metrics.MonitoringCommand;

import java.nio.file.Path;
import java.util.Map;

/**
 * @author Aurelien Broszniowski
 */

public class MonitoringInstance {

  private final Path workingPath;
  private final HardwareMetricsCollector hardwareMetricsCollector = new HardwareMetricsCollector();

  public MonitoringInstance(Path workingPath) {
    this.workingPath = workingPath;
  }

  public void startHardwareMonitoring(Map<HardwareMetric, MonitoringCommand> commands) {
    hardwareMetricsCollector.startMonitoring(workingPath, commands);
  }

  public void stopHardwareMonitoring() {
    hardwareMetricsCollector.stopMonitoring();
  }

  public boolean isMonitoringRunning(HardwareMetric hardwareMetric) {
    return hardwareMetricsCollector.isMonitoringRunning(hardwareMetric);
  }
}

