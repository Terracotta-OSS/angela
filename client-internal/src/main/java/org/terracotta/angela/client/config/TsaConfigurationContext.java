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
package org.terracotta.angela.client.config;

import org.terracotta.angela.common.TerracottaCommandLineEnvironment;
import org.terracotta.angela.common.tcconfig.License;
import org.terracotta.angela.common.topology.Topology;

import java.time.Duration;

public interface TsaConfigurationContext {
  Topology getTopology();

  License getLicense();

  String getClusterName();

  TerracottaCommandLineEnvironment getTerracottaCommandLineEnvironment(String whatFor);

  /**
   * TSA will be killed if notthing happens in a specified amount of time
   */
  Duration getInactivityKillerDelay();

  interface TerracottaCommandLineEnvironmentKeys {
    String JCMD = "Jcmd";
    String SERVER_START_PREFIX = "Server-";
    String SERVER_STOP_PREFIX = "Stop-";
  }
}
