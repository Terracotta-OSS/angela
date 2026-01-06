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
package org.terracotta.angela;

import org.junit.Test;
import org.terracotta.angela.client.ClusterFactory;
import org.terracotta.angela.client.ClusterMonitor;
import org.terracotta.angela.client.Cmd;
import org.terracotta.angela.client.Tsa;
import org.terracotta.angela.client.config.ConfigurationContext;
import org.terracotta.angela.client.config.custom.CustomConfigurationContext;
import org.terracotta.angela.common.TerracottaServerState;
import org.terracotta.angela.common.ToolExecutionResult;
import org.terracotta.angela.common.metrics.HardwareMetric;
import org.terracotta.angela.common.tcconfig.TcConfig;
import org.terracotta.angela.common.tcconfig.TerracottaServer;
import org.terracotta.angela.common.topology.Topology;
import org.terracotta.angela.common.util.IpUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumSet;
import java.util.UUID;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.fail;
import static org.terracotta.angela.common.AngelaProperties.KIT_COPY;
import static org.terracotta.angela.common.TerracottaServerState.STARTED_AS_ACTIVE;
import static org.terracotta.angela.common.TerracottaServerState.STARTING;
import static org.terracotta.angela.common.TerracottaServerState.STOPPED;
import static org.terracotta.angela.common.tcconfig.TcConfig.tcConfig;
import static org.terracotta.angela.common.topology.Version.version;
import static org.terracotta.angela.util.TestUtils.TC_CONFIG_A;
import static org.terracotta.angela.util.TestUtils.TC_CONFIG_AP;
import static org.terracotta.angela.util.Versions.EHCACHE_VERSION_XML;

/**
 * @author Aurelien Broszniowski
 */
public class InstallIT extends BaseIT {

  public InstallIT(String mode, String hostname, boolean inline, boolean ssh) {
    super(mode, hostname, inline, ssh);
  }

  @Test
  public void testHardwareMetricsLogs() throws Exception {
    final Path resultPath = Paths.get("target", UUID.randomUUID().toString());
    ConfigurationContext config = CustomConfigurationContext.customConfigurationContext()
            .tsa(tsa -> tsa.topology(new Topology(getOldDistribution(), tcConfig(version(EHCACHE_VERSION_XML), TC_CONFIG_AP))))
            .monitoring(monitoring -> monitoring.commands(EnumSet.of(HardwareMetric.DISK)));

    try (ClusterFactory factory = angelaOrchestrator.newClusterFactory("InstallTest::testHardwareStatsLogs", config)) {
      Tsa tsa = factory.tsa();

      TerracottaServer server = tsa.getServer(0, 0);
      tsa.create(server);
      ClusterMonitor monitor = factory.monitor().startOnAll();

      Thread.sleep(3000);

      monitor.downloadTo(resultPath);
    }

    assertThat(Files.isRegularFile(resultPath.resolve(IpUtils.getHostName()).resolve("disk-stats.log")), is(true));
  }

  @Test
  public void testRemoteInstall() throws Exception {
    TcConfig tcConfig = tcConfig(version(EHCACHE_VERSION_XML), TC_CONFIG_A);
    tcConfig.updateServerHost(0, hostname);

    ConfigurationContext config = CustomConfigurationContext.customConfigurationContext()
            .tsa(tsa -> tsa.topology(new Topology(getOldDistribution(), tcConfig)));

    try (ClusterFactory factory = angelaOrchestrator.newClusterFactory("InstallTest::testSsh", config)) {
      Tsa tsa = factory.tsa();
      tsa.startAll();
    }
  }

  @Test
  public void testLocalInstall() throws Exception {
    ConfigurationContext config = CustomConfigurationContext.customConfigurationContext()
            .tsa(tsa -> tsa.topology(new Topology(getOldDistribution(), tcConfig(version(EHCACHE_VERSION_XML), TC_CONFIG_A))));

    try (ClusterFactory factory = angelaOrchestrator.newClusterFactory("InstallTest::testLocalInstall", config)) {
      Tsa tsa = factory.tsa();
      tsa.startAll();
    }
  }

  @Test
  public void testLocalCmd() throws Exception {
    ConfigurationContext config = CustomConfigurationContext.customConfigurationContext()
            .tsa(tsa -> tsa.topology(new Topology(getOldDistribution(), tcConfig(version(EHCACHE_VERSION_XML), TC_CONFIG_A))));

    System.setProperty(KIT_COPY.getPropertyName(), "true");

    try (ClusterFactory factory = angelaOrchestrator.newClusterFactory("InstallTest::testLocalCmd", config)) {
      Tsa tsa = factory.tsa();
      final Cmd cmd = tsa.cmd(tsa.getServer(0, 0));
      final ToolExecutionResult toolExecutionResult = cmd.executeCommand("./voter/bin/base-voter");

      assertThat(toolExecutionResult.getExitStatus(), is(1));
    }
  }

  @Test
  public void testTwoTsaCustomConfigsFailWithoutMultiConfig() {
    Topology topology1 = new Topology(getOldDistribution(), tcConfig(version(EHCACHE_VERSION_XML), TC_CONFIG_A));
    Topology topology2 = new Topology(getOldDistribution(), tcConfig(version(EHCACHE_VERSION_XML), TC_CONFIG_AP));

    try {
      CustomConfigurationContext.customConfigurationContext()
              .tsa(tsa -> tsa.topology(topology1))
              .tsa(tsa -> tsa.topology(topology2));
      fail("expected IllegalStateException");
    } catch (IllegalStateException ise) {
      // expected
    }
  }

  @Test
  public void testStopStalledServer() throws Exception {
    ConfigurationContext config = CustomConfigurationContext.customConfigurationContext()
            .tsa(tsa -> tsa.topology(new Topology(getOldDistribution(), tcConfig(version(EHCACHE_VERSION_XML), getClass().getResource("/configs/tc-config-ap-consistent.xml"))))
            );

    try (ClusterFactory factory = angelaOrchestrator.newClusterFactory("InstallTest::testStopStalledServer", config)) {
      Tsa tsa = factory.tsa();

      TerracottaServer server = tsa.getServer(0, 0);
      tsa.create(server);

      assertThat(tsa.getState(server), is(STARTING));

      tsa.stop(server);
      assertThat(tsa.getState(server), is(STOPPED));
    }
  }

  @Test
  public void testStartCreatedServer() throws Exception {
    ConfigurationContext config = CustomConfigurationContext.customConfigurationContext()
            .tsa(tsa -> tsa.topology(new Topology(getOldDistribution(), tcConfig(version(EHCACHE_VERSION_XML), TC_CONFIG_A)))
            );

    try (ClusterFactory factory = angelaOrchestrator.newClusterFactory("InstallTest::testStartCreatedServer", config)) {
      Tsa tsa = factory.tsa();

      TerracottaServer server = tsa.getServer(0, 0);
      tsa.create(server);
      tsa.start(server);
      assertThat(tsa.getState(server), is(STARTED_AS_ACTIVE));
    }
  }

  @Test(expected = RuntimeException.class)
  public void testServerStartUpWithArg() throws Exception {
    ConfigurationContext config = CustomConfigurationContext.customConfigurationContext()
            .tsa(tsa -> tsa.topology(new Topology(getOldDistribution(), tcConfig(version(EHCACHE_VERSION_XML), TC_CONFIG_A)))
            );

    try (ClusterFactory factory = angelaOrchestrator.newClusterFactory("InstallTest::testStartCreatedServer", config)) {
      Tsa tsa = factory.tsa();

      TerracottaServer server = tsa.getServer(0, 0);
      // Server start-up must fail due to unknown argument passed
      tsa.start(server, "--some-unknown-argument");
    }
  }

  @Test
  public void testStopPassive() throws Exception {
    TcConfig tcConfig = tcConfig(version(EHCACHE_VERSION_XML), TC_CONFIG_AP);
    tcConfig.updateServerHost(0, hostname);
    tcConfig.updateServerHost(1, hostname);

    ConfigurationContext config = CustomConfigurationContext.customConfigurationContext()
            .tsa(tsa -> tsa.topology(new Topology(getOldDistribution(), tcConfig))
            );

    try (ClusterFactory factory = angelaOrchestrator.newClusterFactory("InstallTest::testStopPassive", config)) {
      Tsa tsa = factory.tsa();
      tsa.spawnAll();
      tsa.waitForPassive();

      TerracottaServer passive = tsa.getPassive();
      tsa.stop(passive);

      assertThat(tsa.getState(passive), is(TerracottaServerState.STOPPED));
      assertThat(tsa.getPassive(), is(nullValue()));

      tsa.start(passive);
      assertThat(tsa.getState(passive), is(TerracottaServerState.STARTED_AS_PASSIVE));

      TerracottaServer active = tsa.getActive();
      assertThat(tsa.getState(active), is(TerracottaServerState.STARTED_AS_ACTIVE));
      tsa.stop(active);
      assertThat(tsa.getState(active), is(TerracottaServerState.STOPPED));

      await().atMost(15, SECONDS).until(() -> tsa.getState(passive), is(TerracottaServerState.STARTED_AS_ACTIVE));
    }
  }



}
