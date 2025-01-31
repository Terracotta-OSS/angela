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
import org.terracotta.angela.client.Client;
import org.terracotta.angela.client.ClientArray;
import org.terracotta.angela.client.ClientArrayFuture;
import org.terracotta.angela.client.ClientJob;
import org.terracotta.angela.client.ClusterFactory;
import org.terracotta.angela.client.ClusterMonitor;
import org.terracotta.angela.client.config.ConfigurationContext;
import org.terracotta.angela.common.clientconfig.ClientArrayConfig;
import org.terracotta.angela.common.clientconfig.ClientId;
import org.terracotta.angela.common.cluster.AtomicCounter;
import org.terracotta.angela.common.cluster.AtomicReference;
import org.terracotta.angela.common.cluster.Barrier;
import org.terracotta.angela.common.cluster.Cluster;
import org.terracotta.angela.common.distribution.Distribution;
import org.terracotta.angela.common.metrics.HardwareMetric;
import org.terracotta.angela.common.metrics.MonitoringCommand;
import org.terracotta.angela.common.topology.ClientArrayTopology;
import org.terracotta.angela.common.topology.LicenseType;
import org.terracotta.angela.common.topology.PackageType;
import org.terracotta.angela.common.topology.Topology;
import org.terracotta.angela.util.Versions;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static java.lang.Thread.sleep;
import static org.hamcrest.CoreMatchers.anyOf;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.number.OrderingComparison.greaterThan;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeNotNull;
import static org.terracotta.angela.client.config.custom.CustomConfigurationContext.customConfigurationContext;
import static org.terracotta.angela.common.clientconfig.ClientArrayConfig.newClientArrayConfig;
import static org.terracotta.angela.common.distribution.Distribution.distribution;
import static org.terracotta.angela.common.tcconfig.TcConfig.tcConfig;
import static org.terracotta.angela.common.topology.Version.version;
import static org.terracotta.angela.util.TestUtils.TC_CONFIG_A;

public class ClientIT extends BaseIT {

  public ClientIT(String mode, String hostname, boolean inline, boolean ssh) {
    super(mode, hostname, inline, ssh);
  }

  @Test
  public void testClientArrayDownloadFiles() throws Exception {
    assumeFalse("Cannot run without Ignite when using client jobs", agentID.isLocal());

    final String cliSymbName = "foo";
    ConfigurationContext configContext = customConfigurationContext()
        .clientArray(clientArray -> clientArray.clientArrayTopology(new ClientArrayTopology(newClientArrayConfig().host(cliSymbName, hostname))));

    String remoteFolder = "testFolder";
    String downloadedFile = "myNewFile.txt";
    String fileContent = "Test data";
    String localFolder = "target/myNewFolderClient";

    try (ClusterFactory instance = angelaOrchestrator.newClusterFactory("ClientTest::testClientArrayDownloadFiles", configContext)) {
      try (ClientArray clientArray = instance.clientArray(0)) {
        ClientArrayFuture f = clientArray.executeOnAll((cluster) -> {
          new File(remoteFolder).mkdirs();
          Files.write(Paths.get(remoteFolder, downloadedFile), fileContent.getBytes());
        });
        f.get();
        clientArray.download(remoteFolder, Paths.get(localFolder));
        Path downloadPath = Paths.get(localFolder, cliSymbName, downloadedFile);
        String downloadedFileContent = new String(Files.readAllBytes(downloadPath));
        assertThat(downloadedFileContent, is(equalTo(fileContent)));
      }
    }
  }

  @Test
  public void testMultipleClientsSameHostArrayDownloadFiles() throws Exception {
    assumeFalse("Cannot run without Ignite when using client jobs", agentID.isLocal());

    int clientsCount = 3;
    ConfigurationContext configContext = customConfigurationContext()
        .clientArray(clientArray -> clientArray.clientArrayTopology(new ClientArrayTopology(newClientArrayConfig().hostSerie(clientsCount, hostname))));

    String remoteFolder = "testFolder";
    String downloadedFile = "myNewFile.txt";
    String fileContent = "Test data";
    String localFolder = "target/myNewFolderMultipleClients";

    try (ClusterFactory instance = angelaOrchestrator.newClusterFactory("ClientTest::testMultipleClientsSameHostArrayDownloadFiles", configContext)) {
      try (ClientArray clientArray = instance.clientArray(0)) {
        Set<String> filecontents = new HashSet<>();

        ClientArrayFuture f = clientArray.executeOnAll((cluster) -> {
          String clientFileContent = fileContent + UUID.randomUUID();
          filecontents.add(clientFileContent);
          System.out.println("Writing to file : " + clientFileContent);
          new File(remoteFolder).mkdirs();
          Files.write(Paths.get(remoteFolder, downloadedFile), clientFileContent.getBytes());
          System.out.println("Done");
        });
        f.get();
        clientArray.download(remoteFolder, Paths.get((localFolder)));

        for (Client client : clientArray.getClients()) {
          Path downloadPath = Paths.get(localFolder, client.getSymbolicName(), downloadedFile);
          String downloadedFileContent = new String(Files.readAllBytes(downloadPath));
          filecontents.remove(downloadedFileContent);
        }
        assertThat(filecontents.size(), is(equalTo(0)));
      }
    }
  }

  @Test
  public void testMultipleClientJobsSameHostDownloadFiles() throws Exception {
    assumeFalse("Cannot run without Ignite when using client jobs", agentID.isLocal());

    int clientsCount = 3;
    int clientsPerMachine = 2;
    ConfigurationContext configContext = customConfigurationContext()
        .clientArray(clientArray -> clientArray.clientArrayTopology(new ClientArrayTopology(newClientArrayConfig().hostSerie(clientsCount, hostname))));

    String remoteFolder = "testFolder";
    String downloadedFile = "myNewFile.txt";
    String fileContent = "Test data";
    String localFolder = "target/myNewFolderMultipleJobs";

    try (ClusterFactory instance = angelaOrchestrator.newClusterFactory("ClientTest::testMultipleClientJobsSameHostDownloadFiles", configContext)) {
      try (ClientArray clientArray = instance.clientArray(0)) {
        Set<String> filecontents = new HashSet<>();

        ClientJob clientJob = (cluster) -> {
          String clientFileContent = fileContent + UUID.randomUUID();
          filecontents.add(clientFileContent);
          System.out.println("Writing to file : " + clientFileContent);
          String symbolicName = cluster.getClientId()
              .getSymbolicName()
              .getSymbolicName();
          long jobNumber = cluster.atomicCounter(symbolicName, 0)
              .incrementAndGet();
          File jobFolder = new File(remoteFolder, Long.toString(jobNumber));
          jobFolder.mkdirs();
          Path path = Paths.get(jobFolder.getPath(), downloadedFile);
          System.out.println("REMOTE PATH: " + path);
          Files.write(path, clientFileContent.getBytes());
          System.out.println("Done");
        };
        ClientArrayFuture f = clientArray.executeOnAll(clientJob, clientsPerMachine);
        f.get();
        clientArray.download(remoteFolder, Paths.get((localFolder)));

        for (Client client : clientArray.getClients()) {
          String symbolicName = client.getSymbolicName();
          for (Integer jobNumber = 1; jobNumber <= 2; jobNumber++) {
            Path downloadPath = Paths.get(localFolder, symbolicName, jobNumber.toString(), downloadedFile);
            String downloadedFileContent = new String(Files.readAllBytes(downloadPath));
            filecontents.remove(downloadedFileContent);
          }
        }
        assertThat(filecontents.size(), is(equalTo(0)));
      }
    }
  }

  @Test
  public void testMultipleClientsOnSameHost() throws Exception {
    assumeFalse("Cannot run without Ignite when using client jobs", agentID.isLocal());

    Distribution distribution = getOldDistribution();
    ConfigurationContext configContext = customConfigurationContext()
        .clientArray(clientArray -> clientArray
            .clientArrayTopology(new ClientArrayTopology(distribution, newClientArrayConfig()
                .hostSerie(3, hostname)
            )));

    try (ClusterFactory instance = angelaOrchestrator.newClusterFactory("ClientTest::testMultipleClientsOnSameHost", configContext)) {
      try (ClientArray clientArray = instance.clientArray(0)) {
        ClientArrayFuture f = clientArray.executeOnAll((cluster) -> System.out.println("hello world"));
        f.get();
      }
    }
  }

  @Test
  public void testMultipleClientJobsOnSameMachine() throws Exception {
    assumeFalse("Cannot run without Ignite when using client jobs", agentID.isLocal());

    int serieLength = 3;
    int clientsPerMachine = 2;
    Distribution distribution = getOldDistribution();
    ConfigurationContext configContext = customConfigurationContext()
        .clientArray(clientArray -> clientArray
            .clientArrayTopology(
                new ClientArrayTopology(
                    distribution,
                    newClientArrayConfig().hostSerie(serieLength, hostname)
                )
            )
        );
    try (ClusterFactory factory = angelaOrchestrator.newClusterFactory("ClientTest::testMultipleClientJobsOnSameMachine", configContext)) {
      try (ClientArray clientArray = factory.clientArray(0)) {
        ClientJob clientJob = (cluster) -> {
          AtomicCounter counter = cluster.atomicCounter("countJobs", 0);
          long n = counter.incrementAndGet();
          System.out.println("hello world from job number " + n);
        };
        ClientArrayFuture f = clientArray.executeOnAll(clientJob, clientsPerMachine);
        f.get();

        long expectedJobs = clientsPerMachine * serieLength;
        long actualJobs = factory.cluster()
            .atomicCounter("countJobs", 0)
            .get();
        assertThat(actualJobs, is(expectedJobs));
      }
    }
  }

  @Test
  public void testRemoteClient() throws Exception {
    assumeFalse("Cannot run without Ignite when using client jobs", agentID.isLocal());

    Distribution distribution = getOldDistribution();
    ConfigurationContext configContext = customConfigurationContext()
        .clientArray(clientArray -> clientArray
            .clientArrayTopology(new ClientArrayTopology(distribution, newClientArrayConfig().host("foo", hostname))))
        .clientArray(clientArray -> clientArray
            .clientArrayTopology(new ClientArrayTopology(distribution, newClientArrayConfig().host("bar", hostname))));

    try (ClusterFactory instance = angelaOrchestrator.newClusterFactory("ClientTest::testRemoteClient", configContext)) {
      try (ClientArray clientArray = instance.clientArray(0)) {
        ClientArrayFuture f = clientArray.executeOnAll((cluster) -> System.out.println("hello world 1"));
        f.get();
      }
      try (ClientArray clientArray = instance.clientArray(0)) {
        ClientArrayFuture f = clientArray.executeOnAll((cluster) -> System.out.println("hello world 2"));
        f.get();
      }
    }
  }

  @Test
  public void testClientArrayNoDistribution() throws Exception {
    assumeFalse("Cannot run without Ignite when using client jobs", agentID.isLocal());

    ConfigurationContext configContext = customConfigurationContext()
        .clientArray(clientArray -> clientArray.clientArrayTopology(new ClientArrayTopology(newClientArrayConfig().host("localhost", hostname))));

    try (ClusterFactory instance = angelaOrchestrator.newClusterFactory("ClientTest::testClientArrayNoDistribution", configContext)) {
      try (ClientArray clientArray = instance.clientArray(0)) {
        ClientArrayFuture f = clientArray.executeOnAll((cluster) -> System.out.println("hello world 1"));
        f.get();
      }
    }
  }

  @Test
  public void testClientArrayExceptionReported() throws Exception {
    assumeFalse("Cannot run without Ignite when using client jobs", agentID.isLocal());

    ConfigurationContext configContext = customConfigurationContext()
        .clientArray(clientArray -> clientArray.clientArrayTopology(new ClientArrayTopology(newClientArrayConfig().hostSerie(2, hostname))));

    try (ClusterFactory instance = angelaOrchestrator.newClusterFactory("ClientTest::testClientArrayExceptionReported", configContext)) {
      try (ClientArray clientArray = instance.clientArray(0)) {
        ClientArrayFuture f = clientArray.executeOnAll((cluster) -> {
          String message = "Just Say No (tm) " + cluster.atomicCounter("testClientArrayExceptionReportedCounter", 0L)
              .getAndIncrement();
          throw new RuntimeException(message);
        });
        try {
          f.get();
          fail("expected ExecutionException");
        } catch (ExecutionException ee) {
          assertThat(exceptionToString(ee), containsString("Just Say No (tm) 0"));
          assertThat(exceptionToString(ee), containsString("Just Say No (tm) 1"));
        }
      }
    }
  }

  private static String exceptionToString(Throwable t) {
    StringWriter sw = new StringWriter();
    PrintWriter pw = new PrintWriter(sw);
    t.printStackTrace(pw);
    pw.close();
    return sw.toString();
  }

  @Test
  public void testClientCpuMetricsLogs() throws Exception {
    assumeFalse("Cannot run without Ignite when using client jobs", agentID.isLocal());

    final Path resultPath = Paths.get("target", UUID.randomUUID().toString());

    final String cliSymbName = "foo";
    ClientArrayTopology ct = new ClientArrayTopology(getOldDistribution(),
        newClientArrayConfig().host(cliSymbName, hostname));

    ConfigurationContext configContext = customConfigurationContext()
        .clientArray(clientArray -> clientArray.license(LicenseType.TERRACOTTA_OS.defaultLicense()).clientArrayTopology(ct))
        .monitoring(monitoring -> monitoring.commands(EnumSet.of(HardwareMetric.CPU)));

    try (ClusterFactory factory = angelaOrchestrator.newClusterFactory("ClientTest::testClientCpuMetricsLogs", configContext)) {
      ClusterMonitor monitor = factory.monitor();
      ClientJob clientJob = (cluster) -> {
        sleep(18000);
      };

      ClientArray clientArray = factory.clientArray(0);
      monitor.startOnAll();

      ClientArrayFuture future = clientArray.executeOnAll(clientJob);
      future.get();

      monitor.downloadTo(resultPath);
      monitor.stopOnAll();

      monitor.processMetrics((hostName, transportableFile) -> {
        assertThat(hostName, is(hostName));
        assertThat(transportableFile.getName(), is("cpu-stats.log"));
        byte[] content = transportableFile.getContent();
        assertNotNull(content);
        assertThat(content.length, greaterThan(0));
      });
    }
  }

  @Test
  public void testClientAllHardwareMetricsLogs() throws Exception {
    assumeFalse("Cannot run without Ignite when using client jobs", agentID.isLocal());

    final Path resultPath = Paths.get("target", UUID.randomUUID().toString());

    final String cliSymbName = "foo";
    ClientArrayTopology ct = new ClientArrayTopology(getOldDistribution(),
        newClientArrayConfig().host(cliSymbName, hostname));

    ConfigurationContext configContext = customConfigurationContext()
        .clientArray(clientArray -> clientArray.license(LicenseType.TERRACOTTA_OS.defaultLicense()).clientArrayTopology(ct))
        .monitoring(monitoring -> monitoring.commands(EnumSet.allOf(HardwareMetric.class)));

    try (ClusterFactory factory = angelaOrchestrator.newClusterFactory("ClientTest::testClientAllHardwareMetricsLogs", configContext)) {
      ClusterMonitor monitor = factory.monitor();
      monitor.startOnAll();

      sleep(18000);

      monitor.downloadTo(resultPath);
      monitor.stopOnAll();
    }

    final Path statFile = resultPath.resolve(hostname);
    assertMetricsFile(statFile.resolve("cpu-stats.log"));
    assertMetricsFile(statFile.resolve("disk-stats.log"));
    assertMetricsFile(statFile.resolve("memory-stats.log"));
    assertMetricsFile(statFile.resolve("network-stats.log"));
  }

  @Test
  public void testClientDummyMemoryMetrics() throws Exception {
    assumeFalse("Cannot run without Ignite when using client jobs", agentID.isLocal());

    final Path resultPath = Paths.get("target", UUID.randomUUID().toString());

    final String cliSymbName = "foo";
    ClientArrayTopology ct = new ClientArrayTopology(getOldDistribution(),
        newClientArrayConfig().host(cliSymbName, hostname));

    HardwareMetric metric = HardwareMetric.MEMORY;
    ConfigurationContext configContext = customConfigurationContext()
        .clientArray(clientArray -> clientArray.license(LicenseType.TERRACOTTA_OS.defaultLicense()).clientArrayTopology(ct))
        .monitoring(monitoring -> monitoring.command(metric, new MonitoringCommand("dummy", "command")));

    try (ClusterFactory factory = angelaOrchestrator.newClusterFactory("ClientTest::testClientDummyMemoryMetrics", configContext)) {
      ClusterMonitor monitor = factory.monitor();
      ClientJob clientJob = (cluster) -> {
        sleep(18000);
      };

      ClientArray clientArray = factory.clientArray(0);
      monitor.startOnAll();

      ClientArrayFuture future = clientArray.executeOnAll(clientJob);
      future.get();

      monitor.downloadTo(resultPath);
      assertThat(monitor.isMonitoringRunning(metric), is(false));

      monitor.stopOnAll();
    }
  }

  @Test(expected = IllegalArgumentException.class)
  public void testClusterMonitorWhenNoMonitoringSpecified() throws Exception {
    ClientArrayTopology ct = new ClientArrayTopology(getOldDistribution(),
        newClientArrayConfig().host("foo", hostname));

    ConfigurationContext configContext = customConfigurationContext()
        .clientArray(clientArray -> clientArray.license(LicenseType.TERRACOTTA_OS.defaultLicense()).clientArrayTopology(ct));

    try (ClusterFactory factory = angelaOrchestrator.newClusterFactory("ClientTest::testClusterMonitorWhenNoMonitoringSpecified", configContext)) {
      factory.monitor();
    }
  }

  @Test
  public void testMixingLocalhostWithRemote() throws Exception {
    assumeNotNull("Cannot only run with default Ignite config supporting remoting", sshServer);

    ConfigurationContext configContext = customConfigurationContext()
        .tsa(tsa -> tsa.topology(new Topology(getOldDistribution(),
                tcConfig(version(Versions.EHCACHE_VERSION_XML), TC_CONFIG_A)))
            .license(LicenseType.TERRACOTTA_OS.defaultLicense())
        )
        .clientArray(clientArray -> clientArray.license(LicenseType.TERRACOTTA_OS.defaultLicense())
            .clientArrayTopology(new ClientArrayTopology(distribution(version(Versions.EHCACHE_VERSION_XML), PackageType.KIT, LicenseType.TERRACOTTA_OS), newClientArrayConfig()
                .host("foo", "inexisting-server")))
        );

    try (ClusterFactory factory = angelaOrchestrator.newClusterFactory("ClientTest::testMixingLocalhostWithRemote", configContext)) {
      factory.tsa();

      try {
        factory.clientArray(0);
        fail("expected exception");
      } catch (Exception e) {
        // expected
      }
    }
  }

  @Test
  public void testBarrier() throws Exception {
    assumeFalse("Cannot run without Ignite when using client jobs", agentID.isLocal());

    final int clientCount = 2;
    final int loopCount = 20;
    Distribution distribution = getOldDistribution();
    ConfigurationContext configContext = customConfigurationContext()
        .clientArray(clientArray -> clientArray
            .clientArrayTopology(new ClientArrayTopology(distribution, newClientArrayConfig().host("foo", hostname))))
        .clientArray(clientArray -> clientArray
            .clientArrayTopology(new ClientArrayTopology(distribution, newClientArrayConfig().host("bar", hostname))));

    try (ClusterFactory factory = angelaOrchestrator.newClusterFactory("ClientTest::testBarrier", configContext)) {

      ClientJob clientJob = cluster -> {
        Barrier daBarrier = cluster.barrier("daBarrier", clientCount);
        for (int i = 0; i < loopCount; i++) {
          daBarrier.await();
          AtomicCounter counter = cluster.atomicCounter("ClientTest::testBarrier::counter", 0L);
          counter.incrementAndGet();
        }
      };

      List<Future<Void>> futures = new ArrayList<>();
      for (int i = 0; i < clientCount; i++) {
        ClientArray clients = factory.clientArray(i);
        ClientArrayFuture caf = clients.executeOnAll(clientJob);
        futures.addAll(caf.getFutures());
      }

      // if the barrier hangs forever, one of those futures will timeout on get and throw
      for (Future<Void> future : futures) {
        future.get(30, TimeUnit.SECONDS);
      }

      AtomicCounter counter = factory.cluster().atomicCounter("ClientTest::testBarrier::counter", 0L);
      assertThat(counter.get(), is((long) clientCount * loopCount));
    }
  }

  @Test
  public void testUploadClientJars() throws Exception {
    assumeFalse("Cannot run without Ignite when using client jobs", agentID.isLocal());

    Distribution distribution = getOldDistribution();
    ClientArrayConfig clientArrayConfig1 = newClientArrayConfig()
        .host("client2", hostname)
        .named("client2-2");

    ClientArrayTopology ct = new ClientArrayTopology(distribution, clientArrayConfig1);

    ConfigurationContext configContext = customConfigurationContext()
        .clientArray(clientArray -> clientArray.license(LicenseType.TERRACOTTA_OS.defaultLicense()).clientArrayTopology(ct));

    try (ClusterFactory factory = angelaOrchestrator.newClusterFactory("ClientTest::testUploadClientJars", configContext)) {
      ClientJob clientJob = (cluster) -> {
        sleep(1000);
      };

      { // executeAll
        ClientArray clientArray = factory.clientArray(0);

        ClientArrayFuture f = clientArray.executeOnAll(clientJob);
        f.get();
        Client rc = clientArray.getClients().stream().findFirst().get();

        Path tmp = Paths.get("target", "tmp");
        Files.createDirectories(tmp);
        rc.browse(".").downloadTo(tmp);
      }
    }
  }

  @Test
  public void testClientArrayReferenceShared() throws Exception {
    assumeFalse("Cannot run without Ignite when using client jobs", agentID.isLocal());

    ConfigurationContext configContext = customConfigurationContext()
        .clientArray(clientArray -> clientArray.clientArrayTopology(new ClientArrayTopology(newClientArrayConfig().hostSerie(2, hostname))));

    try (ClusterFactory factory = angelaOrchestrator.newClusterFactory("ClientTest::testClientArrayReferenceShared", configContext)) {
      try (ClientArray clientArray = factory.clientArray(0)) {
        ClientArrayFuture f = clientArray.executeOnAll((cluster) -> {
          AtomicReference<String> strRef = cluster.atomicReference("string", null);
          strRef.set("A");

          AtomicReference<Integer> intRef = cluster.atomicReference("int", 0);
          intRef.compareAndSet(0, 1);
        });
        f.get();
        Cluster cluster = factory.cluster();

        AtomicReference<String> strRef = cluster.atomicReference("string", "X");
        assertThat(strRef.get(), is("A"));

        AtomicReference<Integer> intRef = cluster.atomicReference("int", 0);
        assertThat(intRef.get(), is(1));
      }
    }
  }

  @Test
  public void testClientArrayHostNames() throws Exception {
    assumeFalse("Cannot run without Ignite when using client jobs", agentID.isLocal());

    ClientArrayConfig hostSerie = newClientArrayConfig()
        .hostSerie(2, hostname);
    ConfigurationContext configContext = customConfigurationContext()
        .clientArray(clientArray -> clientArray.clientArrayTopology(new ClientArrayTopology(hostSerie)));
    try (ClusterFactory factory = angelaOrchestrator.newClusterFactory("ClientTest::testClientArrayHostNames", configContext)) {
      try (ClientArray clientArray = factory.clientArray(0)) {
        ClientJob clientJob = (Cluster cluster) -> {
          ClientId clientId = cluster.getClientId();
          assertThat(clientId.getHostName(), is(hostname));
          assertThat(clientId.getSymbolicName().getSymbolicName(),
              anyOf(is(hostname + "-0"), is(hostname + "-1")));
        };
        ClientArrayFuture f = clientArray.executeOnAll(clientJob);
        f.get();
        Cluster cluster = factory.cluster();
        assertNull(cluster.getClientId());
      }
    }
  }

  @Test
  public void testClient_exit_1() throws Exception {
    assumeFalse("Cannot run without Ignite when using client jobs", agentID.isLocal());

    ConfigurationContext configContext = customConfigurationContext()
        .clientArray(clientArray -> clientArray.clientArrayTopology(new ClientArrayTopology(newClientArrayConfig().host("my-client", hostname))));

    try (ClusterFactory factory = angelaOrchestrator.newClusterFactory("ClientTest::testClient_exit", configContext)) {
      final Barrier started = factory.cluster().barrier("started", 2);
      final ClientArrayFuture future = factory.clientArray(0).executeOnAll(cluster -> {
        started.await();
        System.out.println("SHUTDOWN FROM JOB");
        System.exit(1);
      });

      // wait for process to start and job to be received
      started.await();

      // wait a little for the exit to be done
      System.out.println("SLEEP");
      sleep(7_000);

      // wait for result
      System.out.println("WAIT FROM TEST");
      try {
        future.get();
        fail();
      } catch (IllegalStateException e) {
        assertThat(e.getMessage(), containsString("Agent is gone in an abrupt way and Ignite cannot get any result from it anymore"));
      }
    }
  }

  @Test
  public void testClient_exit_2() throws Exception {
    assumeFalse("Cannot run without Ignite when using client jobs", agentID.isLocal());

    ConfigurationContext configContext = customConfigurationContext()
        .clientArray(clientArray -> clientArray.clientArrayTopology(new ClientArrayTopology(newClientArrayConfig().host("my-client", hostname))));

    try (ClusterFactory factory = angelaOrchestrator.newClusterFactory("ClientTest::testClient_exit", configContext)) {
      final ClientArrayFuture future = factory.clientArray(0).executeOnAll(cluster -> {
        System.out.println("SHUTDOWN FROM JOB");
        System.exit(1);
      });
      System.out.println("WAIT FROM TEST");
      try {
        future.get();
        fail();
      } catch (IllegalStateException e) {
        assertThat(e.getMessage(), containsString("Agent is gone in an abrupt way and Ignite cannot get any result from it anymore"));
      }
    }
  }

  @Test
  public void testClient_throw_1() throws Exception {
    assumeFalse("Cannot run without Ignite when using client jobs", agentID.isLocal());

    ConfigurationContext configContext = customConfigurationContext()
        .clientArray(clientArray -> clientArray.clientArrayTopology(new ClientArrayTopology(newClientArrayConfig().host("my-client", hostname))));

    try (ClusterFactory factory = angelaOrchestrator.newClusterFactory("ClientTest::testClient_exit", configContext)) {
      final Barrier started = factory.cluster().barrier("started", 2);
      final ClientArrayFuture future = factory.clientArray(0).executeOnAll(cluster -> {
        started.await();
        System.out.println("THROWING FROM JOB");
        throw new OutOfMemoryError("BOOM");
      });

      // wait for process to start and job to be received
      started.await();

      // wait a little for the exit to be done
      System.out.println("SLEEP");
      sleep(7_000);

      // wait for result
      System.out.println("WAIT FROM TEST");
      try {
        future.get();
        fail();
      } catch (ExecutionException e) {
        assertThat(e.getCause().toString(), containsString("java.lang.OutOfMemoryError: BOOM"));
      }
    }
  }

  @Test
  public void testClient_throw_2() throws Exception {
    assumeFalse("Cannot run without Ignite when using client jobs", agentID.isLocal());

    ConfigurationContext configContext = customConfigurationContext()
        .clientArray(clientArray -> clientArray.clientArrayTopology(new ClientArrayTopology(newClientArrayConfig().host("my-client", hostname))));

    try (ClusterFactory factory = angelaOrchestrator.newClusterFactory("ClientTest::testClient_exit", configContext)) {
      final ClientArrayFuture future = factory.clientArray(0).executeOnAll(cluster -> {
        System.out.println("THROWING FROM JOB");
        throw new OutOfMemoryError("BOOM");
      });
      System.out.println("WAIT FROM TEST");
      try {
        future.get();
        fail();
      } catch (ExecutionException e) {
        assertThat(e.getCause().toString(), containsString("java.lang.OutOfMemoryError: BOOM"));
      }
    }
  }

  private void assertMetricsFile(Path path) throws IOException {
    assertThat(Files.exists(path), is(true));
    assertThat(Files.readAllLines(path).size(), is(greaterThan(0)));
  }
}
