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

import org.terracotta.angela.common.util.OS;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

public enum HardwareMetric {

    CPU(HardwareMetric::defaultCpuCommand),
    DISK(HardwareMetric::defaultDiskCommand),
    MEMORY(HardwareMetric::defaultMemoryCommand),
    NETWORK(HardwareMetric::defaultNetworkCommand),
    ;

    private final Supplier<MonitoringCommand> defaultCommandSupplier;

    HardwareMetric(Supplier<MonitoringCommand> defaultCommandSupplier) {
        this.defaultCommandSupplier = defaultCommandSupplier;
    }

    public MonitoringCommand getDefaultMonitoringCommand() {
        return defaultCommandSupplier.get();
    }

    private static MonitoringCommand defaultCpuCommand() {
        if (OS.INSTANCE.isWindows()) {
            return windowsTypeperf(2, "\\Processor(_Total)\\% Processor Time");
        }
        return new MonitoringCommand("mpstat", "-P", "ALL", "10");
    }

    private static MonitoringCommand defaultDiskCommand() {
        if (OS.INSTANCE.isWindows()) {
            return windowsTypeperf(10, "\\PhysicalDisk(_Total)\\Disk Transfers/sec");
        }
        return new MonitoringCommand("iostat", "-h", "-d", "10");
    }

    private static MonitoringCommand defaultMemoryCommand() {
        if (OS.INSTANCE.isWindows()) {
            return windowsTypeperf(10, "\\Memory\\Available MBytes", "\\Memory\\Committed Bytes");
        }
        if (OS.INSTANCE.isMac()) {
            return new MonitoringCommand("vm_stat", "10");
        }
        return new MonitoringCommand("free", "-h", "-s", "10");
    }

    private static MonitoringCommand defaultNetworkCommand() {
        if (OS.INSTANCE.isWindows()) {
            return windowsTypeperf(10, "\\Network Interface(*)\\Bytes Total/sec");
        }
        return new MonitoringCommand("sar", "-n", "DEV", "10");
    }

    private static MonitoringCommand windowsTypeperf(int intervalSeconds, String... counters) {
        List<String> args = new ArrayList<>();
        args.add("typeperf");
        args.addAll(Arrays.asList(counters));
        args.add("-si");
        args.add(Integer.toString(intervalSeconds));
        args.add("-y");
        return new MonitoringCommand(args);
    }
}
