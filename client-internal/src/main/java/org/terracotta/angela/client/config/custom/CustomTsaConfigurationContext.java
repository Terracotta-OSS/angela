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
package org.terracotta.angela.client.config.custom;

import org.terracotta.angela.client.config.TsaConfigurationContext;
import org.terracotta.angela.common.TerracottaCommandLineEnvironment;
import org.terracotta.angela.common.tcconfig.License;
import org.terracotta.angela.common.topology.Topology;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

public class CustomTsaConfigurationContext implements TsaConfigurationContext {
  private Topology topology;
  private License license;
  private String clusterName;
  private final Map<String, TerracottaCommandLineEnvironment> terracottaCommandLineEnvironments = new HashMap<>();
  private TerracottaCommandLineEnvironment defaultTerracottaCommandLineEnvironment = TerracottaCommandLineEnvironment.DEFAULT;
  private Duration inactivityKillerDelay = Duration.ZERO; // disabled

  protected CustomTsaConfigurationContext() {
  }

  @Override
  public Topology getTopology() {
    return topology;
  }

  public CustomTsaConfigurationContext topology(Topology topology) {
    this.topology = topology;
    return this;
  }

  @Override
  public License getLicense() {
    return license;
  }

  public CustomTsaConfigurationContext license(License license) {
    this.license = license;
    return this;
  }

  @Override
  public String getClusterName() {
    return clusterName;
  }

  public CustomTsaConfigurationContext clusterName(String clusterName) {
    this.clusterName = clusterName;
    return this;
  }

  @Override
  public TerracottaCommandLineEnvironment getTerracottaCommandLineEnvironment(String key) {
    TerracottaCommandLineEnvironment tce = terracottaCommandLineEnvironments.get(key);
    return tce != null ? tce : defaultTerracottaCommandLineEnvironment;
  }

  @Override
  public Duration getInactivityKillerDelay() {
    return inactivityKillerDelay;
  }

  /**
   * TSA will be killed if no activity after this period of time in the logs
   */
  public CustomTsaConfigurationContext setInactivityKillerDelay(Duration inactivityKillerDelay) {
    this.inactivityKillerDelay = inactivityKillerDelay;
    return this;
  }

  public CustomTsaConfigurationContext terracottaCommandLineEnvironment(TerracottaCommandLineEnvironment terracottaCommandLineEnvironment) {
    this.defaultTerracottaCommandLineEnvironment = terracottaCommandLineEnvironment;
    return this;
  }

  public CustomTsaConfigurationContext terracottaCommandLineEnvironment(String key, TerracottaCommandLineEnvironment terracottaCommandLineEnvironment) {
    this.terracottaCommandLineEnvironments.put(key, terracottaCommandLineEnvironment);
    return this;
  }
}
