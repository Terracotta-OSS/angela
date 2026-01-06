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

import org.hamcrest.Matcher;
import org.junit.Assume;
import org.junit.Ignore;
import org.junit.Test;
import org.terracotta.angela.client.ClusterFactory;
import org.terracotta.angela.client.ConfigTool;
import org.terracotta.angela.client.Tsa;
import org.terracotta.angela.client.config.ConfigurationContext;
import org.terracotta.angela.client.config.custom.CustomConfigurationContext;
import org.terracotta.angela.common.tcconfig.TerracottaServer;
import org.terracotta.angela.common.topology.Topology;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.time.Duration;
import java.util.concurrent.Callable;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.CoreMatchers.is;
import static org.terracotta.angela.client.config.custom.CustomConfigurationContext.customConfigurationContext;
import static org.terracotta.angela.common.TerracottaConfigTool.configTool;
import static org.terracotta.angela.common.dynamic_cluster.Stripe.stripe;
import static org.terracotta.angela.common.provider.DynamicConfigManager.dynamicCluster;
import static org.terracotta.angela.common.tcconfig.TerracottaServer.server;

public class DynamicClusterIT extends BaseIT {
  private static final Duration TIMEOUT = Duration.ofSeconds(60);
  private static final Duration POLL_INTERVAL = Duration.ofSeconds(1);

  public DynamicClusterIT(String mode, String hostname, boolean inline, boolean ssh) {
    super(mode, hostname, inline, ssh);
  }

  @Test
  public void testNodeStartup() throws Exception {
    ConfigurationContext configContext = customConfigurationContext()
        .tsa(tsa -> tsa
            .topology(
                new Topology(
                    getDistribution(),
                    dynamicCluster(
                        stripe(
                            server("server-1", hostname)
                                .configRepo("terracotta1/repository")
                                .logs("terracotta1/logs")
                                .metaData("terracotta1/metadata")
                                .failoverPriority("availability"),
                            server("server-2", hostname)
                                .configRepo("terracotta2/repository")
                                .logs("terracotta2/logs")
                                .metaData("terracotta2/metadata")
                                .failoverPriority("availability")
                        )
                    )
                )
            )
        );

    try (ClusterFactory factory = angelaOrchestrator.newClusterFactory("DynamicClusterTest::testNodeStartup", configContext)) {
      Tsa tsa = factory.tsa();
      tsa.spawnAll();

      waitFor(() -> tsa.getTsaConfigurationContext().getTopology().getStripes().size(), is(1));
      waitFor(() -> tsa.getTsaConfigurationContext().getTopology().getStripes().get(0).size(), is(2));
      waitFor(() -> tsa.getStarted().size(), is(2));
    }
  }

  @Test
  public void testDynamicNodeAttachToSingleNodeStripe() throws Exception {
    ConfigurationContext configContext = customConfigurationContext()
        .tsa(tsa -> tsa
            .topology(
                new Topology(
                    getDistribution(),
                    dynamicCluster(
                        stripe(
                            server("server-1", hostname)
                                .configRepo("terracotta1/repository")
                                .logs("terracotta1/logs")
                                .metaData("terracotta1/metadata")
                                .failoverPriority("availability")
                        )
                    )
                )
            )
        ).configTool(context -> context.configTool(configTool("configTool", hostname)).distribution(getDistribution()));

    try (ClusterFactory factory = angelaOrchestrator.newClusterFactory("DynamicClusterTest::testDynamicNodeAttachToSingleNodeStripe", configContext)) {
      Tsa tsa = factory.tsa();
      tsa.spawnAll();

      waitFor(() -> tsa.getTsaConfigurationContext().getTopology().getStripes().size(), is(1));
      waitFor(() -> tsa.getTsaConfigurationContext().getTopology().getStripes().get(0).size(), is(1));

      factory.configTool().attachNode(0, server("server-2", hostname)
          .configRepo("terracotta2/repository")
          .logs("terracotta2/logs")
          .metaData("terracotta2/metadata")
          .failoverPriority("availability"));

      waitFor(() -> tsa.getTsaConfigurationContext().getTopology().getStripes().size(), is(1));
      waitFor(() -> tsa.getTsaConfigurationContext().getTopology().getStripes().get(0).size(), is(2));
    }
  }

  @Test
  public void testDynamicNodeAttachToMultiNodeStripe() throws Exception {
    ConfigurationContext configContext = customConfigurationContext()
        .tsa(tsa -> tsa
            .topology(
                new Topology(
                    getDistribution(),
                    dynamicCluster(
                        stripe(
                            server("server-1", hostname)
                                .configRepo("terracotta1/repository")
                                .logs("terracotta1/logs")
                                .metaData("terracotta1/metadata")
                                .failoverPriority("availability"),
                            server("server-2", hostname)
                                .configRepo("terracotta2/repository")
                                .logs("terracotta2/logs")
                                .metaData("terracotta2/metadata")
                                .failoverPriority("availability")
                        )
                    )
                )
            )
        ).configTool(context -> context.configTool(configTool("configTool", hostname)).distribution(getDistribution()));

    try (ClusterFactory factory = angelaOrchestrator.newClusterFactory("DynamicClusterTest::testDynamicNodeAttachToMultiNodeStripe", configContext)) {
      Tsa tsa = factory.tsa();
      tsa.spawnAll();

      waitFor(() -> tsa.getTsaConfigurationContext().getTopology().getStripes().size(), is(1));
      waitFor(() -> tsa.getTsaConfigurationContext().getTopology().getStripes().get(0).size(), is(2));

      factory.configTool().attachNode(0, server("server-3", hostname)
          .configRepo("terracotta3/repository")
          .logs("terracotta3/logs")
          .metaData("terracotta3/metadata")
          .failoverPriority("availability"));

      waitFor(() -> tsa.getTsaConfigurationContext().getTopology().getStripes().size(), is(1));
      waitFor(() -> tsa.getTsaConfigurationContext().getTopology().getStripes().get(0).size(), is(3));
    }
  }

  @Test
  public void testDynamicStripeAttachToSingleStripeCluster() throws Exception {
    ConfigurationContext configContext = customConfigurationContext()
        .tsa(tsa -> tsa
            .topology(
                new Topology(
                    getDistribution(),
                    dynamicCluster(
                        stripe(
                            server("server-1", hostname)
                                .configRepo("terracotta1/repository")
                                .logs("terracotta1/logs")
                                .metaData("terracotta1/metadata")
                                .failoverPriority("availability")
                        )
                    )
                )
            )
        ).configTool(context -> context.configTool(configTool("configTool", hostname)).distribution(getDistribution()));

    try (ClusterFactory factory = angelaOrchestrator.newClusterFactory("DynamicClusterTest::testDynamicStripeAttachToSingleStripeCluster", configContext)) {
      Tsa tsa = factory.tsa();
      tsa.spawnAll();

      waitFor(() -> tsa.getTsaConfigurationContext().getTopology().getStripes().size(), is(1));
      waitFor(() -> tsa.getTsaConfigurationContext().getTopology().getStripes().get(0).size(), is(1));

      factory.configTool().attachStripe(server("server-2", hostname)
          .configRepo("terracotta2/repository")
          .logs("terracotta2/logs")
          .metaData("terracotta2/metadata")
          .failoverPriority("availability"));

      waitFor(() -> tsa.getTsaConfigurationContext().getTopology().getStripes().size(), is(2));
      waitFor(() -> tsa.getTsaConfigurationContext().getTopology().getStripes().get(0).size(), is(1));
      waitFor(() -> tsa.getTsaConfigurationContext().getTopology().getStripes().get(1).size(), is(1));
    }
  }

  @Test
  public void testDynamicStripeAttachToMultiStripeCluster() throws Exception {
    ConfigurationContext configContext = customConfigurationContext()
        .tsa(tsa -> tsa
            .topology(
                new Topology(
                    getDistribution(),
                    dynamicCluster(
                        stripe(
                            server("server-1", hostname)
                                .configRepo("terracotta1/repository")
                                .logs("terracotta1/logs")
                                .metaData("terracotta1/metadata")
                                .failoverPriority("availability")
                        ),
                        stripe(
                            server("server-2", hostname)
                                .configRepo("terracotta2/repository")
                                .logs("terracotta2/logs")
                                .metaData("terracotta2/metadata")
                                .failoverPriority("availability")
                        )
                    )
                )
            )
        ).configTool(context -> context.configTool(configTool("configTool", hostname)).distribution(getDistribution()));

    try (ClusterFactory factory = angelaOrchestrator.newClusterFactory("DynamicClusterTest::testDynamicStripeAttachToMultiStripeCluster", configContext)) {
      Tsa tsa = factory.tsa();
      tsa.spawnAll();

      waitFor(() -> tsa.getTsaConfigurationContext().getTopology().getStripes().size(), is(2));
      waitFor(() -> tsa.getTsaConfigurationContext().getTopology().getStripes().get(0).size(), is(1));
      waitFor(() -> tsa.getTsaConfigurationContext().getTopology().getStripes().get(1).size(), is(1));

      factory.configTool().attachStripe(server("server-3", hostname)
          .configRepo("terracotta3/repository")
          .logs("terracotta3/logs")
          .metaData("terracotta3/metadata")
          .failoverPriority("availability"));

      waitFor(() -> tsa.getTsaConfigurationContext().getTopology().getStripes().size(), is(3));
      waitFor(() -> tsa.getTsaConfigurationContext().getTopology().getStripes().get(0).size(), is(1));
      waitFor(() -> tsa.getTsaConfigurationContext().getTopology().getStripes().get(1).size(), is(1));
    }
  }

  @Test
  public void testSingleStripeFormation() throws Exception {
    ConfigurationContext configContext = customConfigurationContext()
        .tsa(tsa -> tsa
            .topology(
                new Topology(
                    getDistribution(),
                    dynamicCluster(
                        stripe(
                            server("server-1", hostname)
                                .configRepo("terracotta1/repository")
                                .logs("terracotta1/logs")
                                .metaData("terracotta1/metadata")
                                .failoverPriority("availability"),
                            server("server-2", hostname)
                                .configRepo("terracotta2/repository")
                                .logs("terracotta2/logs")
                                .metaData("terracotta2/metadata")
                                .failoverPriority("availability")
                        )
                    )
                )
            )
        ).configTool(context -> context.configTool(configTool("configTool", hostname)).distribution(getDistribution()));


    try (ClusterFactory factory = angelaOrchestrator.newClusterFactory("DynamicClusterTest::testSingleStripeFormation", configContext)) {
      Tsa tsa = factory.tsa();
      tsa.spawnAll();
      factory.configTool().attachAll();

      waitFor(() -> tsa.getTsaConfigurationContext().getTopology().getStripes().size(), is(1));
      waitFor(() -> tsa.getTsaConfigurationContext().getTopology().getStripes().get(0).size(), is(2));
    }
  }

  @Test
  public void testMultiStripeFormation() throws Exception {
    ConfigurationContext configContext = customConfigurationContext()
        .tsa(tsa -> tsa
            .topology(
                new Topology(
                    getDistribution(),
                    dynamicCluster(
                        stripe(
                            server("server-1", hostname)
                                .configRepo("terracotta1/repository")
                                .logs("terracotta1/logs")
                                .metaData("terracotta1/metadata")
                                .failoverPriority("availability"),
                            server("server-2", hostname)
                                .configRepo("terracotta2/repository")
                                .logs("terracotta2/logs")
                                .metaData("terracotta2/metadata")
                                .failoverPriority("availability")
                        ),
                        stripe(
                            server("server-3", hostname)
                                .configRepo("terracotta3/repository")
                                .logs("terracotta3/logs")
                                .metaData("terracotta3/metadata")
                                .failoverPriority("availability"),
                            server("server-4", hostname)
                                .configRepo("terracotta4/repository")
                                .logs("terracotta4/logs")
                                .metaData("terracotta4/metadata")
                                .failoverPriority("availability")
                        )
                    )
                )
            )
        ).configTool(context -> context.configTool(configTool("configTool", hostname)).distribution(getDistribution()));

    try (ClusterFactory factory = angelaOrchestrator.newClusterFactory("DynamicClusterTest::testMultiStripeFormation", configContext)) {
      Tsa tsa = factory.tsa();
      tsa.spawnAll();
      factory.configTool().attachAll();

      waitFor(() -> tsa.getTsaConfigurationContext().getTopology().getStripes().size(), is(2));
      waitFor(() -> tsa.getTsaConfigurationContext().getTopology().getStripes().get(0).size(), is(2));
      waitFor(() -> tsa.getTsaConfigurationContext().getTopology().getStripes().get(1).size(), is(2));
    }
  }

  @Test
  public void testDynamicStripeDetach() throws Exception {
    ConfigurationContext configContext = customConfigurationContext()
        .tsa(tsa -> tsa
            .topology(
                new Topology(
                    getDistribution(),
                    dynamicCluster(
                        stripe(
                            server("server-1", hostname)
                                .configRepo("terracotta1/repository")
                                .logs("terracotta1/logs")
                                .metaData("terracotta1/metadata")
                                .failoverPriority("availability")
                        ),
                        stripe(
                            server("server-2", hostname)
                                .configRepo("terracotta2/repository")
                                .logs("terracotta2/logs")
                                .metaData("terracotta2/metadata")
                                .failoverPriority("availability")
                        )
                    )
                )
            )
        ).configTool(context -> context.configTool(configTool("configTool", hostname)).distribution(getDistribution()));

    try (ClusterFactory factory = angelaOrchestrator.newClusterFactory("DynamicClusterTest::testDynamicStripeDetach", configContext)) {
      Tsa tsa = factory.tsa();
      tsa.spawnAll();
      ConfigTool configTool = factory.configTool();
      configTool.attachAll();

      waitFor(() -> tsa.getDiagnosticModeSevers().size(), is(2));
      waitFor(() -> tsa.getTsaConfigurationContext().getTopology().getStripes().size(), is(2));
      waitFor(() -> tsa.getTsaConfigurationContext().getTopology().getStripes().get(0).size(), is(1));
      waitFor(() -> tsa.getTsaConfigurationContext().getTopology().getStripes().get(1).size(), is(1));

      TerracottaServer toDetach = tsa.getServer(1, 0);
      configTool.detachStripe(1);
      tsa.stop(toDetach);

      waitFor(() -> tsa.getDiagnosticModeSevers().size(), is(1));
      waitFor(() -> tsa.getTsaConfigurationContext().getTopology().getStripes().size(), is(1));
      waitFor(() -> tsa.getTsaConfigurationContext().getTopology().getStripes().get(0).size(), is(1));
    }
  }

  @Test
  public void testNodeActivation() throws Exception {
    ConfigurationContext configContext = customConfigurationContext()
        .tsa(tsa -> tsa
            .topology(
                new Topology(
                    getDistribution(),
                    dynamicCluster(
                        stripe(
                            server("server-1", hostname)
                                .configRepo("terracotta1/repository")
                                .logs("terracotta1/logs")
                                .metaData("terracotta1/metadata")
                                .failoverPriority("availability"),
                            server("server-2", hostname)
                                .configRepo("terracotta2/repository")
                                .logs("terracotta2/logs")
                                .metaData("terracotta2/metadata")
                                .failoverPriority("availability")
                        )
                    )
                )
            )
        ).configTool(context -> context
            .configTool(configTool("configTool", hostname))
            .distribution(getDistribution()));

    try (ClusterFactory factory = angelaOrchestrator.newClusterFactory("DynamicClusterTest::testNodeActivation", configContext)) {
      Tsa tsa = factory.tsa();
      tsa.spawnAll();
      ConfigTool configTool = factory.configTool();
      configTool.attachAll();
      configTool.activate();

      waitFor(() -> tsa.getDiagnosticModeSevers().size(), is(0));
      waitFor(() -> tsa.getActives().size(), is(1));
      waitFor(() -> tsa.getPassives().size(), is(1));
    }
  }

  @Test
  @Ignore("Fails on Azure (Jenkins) - ipv6 tests will be moved to TcDB tests")
  public void testIpv6() throws Exception {
    Assume.assumeTrue("IPv6 is disabled", isIpv6Enabled());

    CustomConfigurationContext configurationContext = customConfigurationContext()
        .tsa(tsa -> tsa
            .topology(
                new Topology(
                    getDistribution(),
                    dynamicCluster(
                        stripe(
                            server("node-1")
                                .bindAddress("::")
                                .groupBindAddress("::")
                                .logs("terracotta1/logs")
                                .configRepo("terracotta1/repo")
                                .metaData("terracotta1/metadata")
                                .failoverPriority("availability"),
                            server("node-2")
                                .bindAddress("::")
                                .groupBindAddress("::")
                                .logs("terracotta2/logs")
                                .configRepo("terracotta2/repo")
                                .metaData("terracotta2/metadata")
                                .failoverPriority("availability")
                        )
                    )
                )
            )
        ).configTool(context -> context.configTool(configTool("configTool", hostname)).distribution(getDistribution()));

    try (ClusterFactory factory = angelaOrchestrator.newClusterFactory("DynamicClusterTest::testIpv6", configurationContext)) {
      Tsa tsa = factory.tsa();
      tsa.spawnAll();
      waitFor(() -> tsa.getDiagnosticModeSevers().size(), is(2));

      ConfigTool configTool = factory.configTool();
      configTool.attachAll();
      configTool.activate();
      waitFor(() -> tsa.getActives().size(), is(1));
      waitFor(() -> tsa.getPassives().size(), is(1));
    }
  }

  private boolean isIpv6Enabled() {
    try (ServerSocketChannel channel = ServerSocketChannel.open()) {
      channel.bind(new InetSocketAddress(InetAddress.getByName("::1"), 0));
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  private void waitFor(Callable<Integer> condition, Matcher<Integer> matcher) {
    await().atMost(TIMEOUT).pollInterval(POLL_INTERVAL).until(condition, matcher);
  }
}
