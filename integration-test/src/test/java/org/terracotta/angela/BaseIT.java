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

import net.schmizz.sshj.common.IOUtils;
import org.junit.After;
import org.junit.Rule;
import org.junit.rules.Timeout;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.terracotta.angela.agent.com.AgentID;
import org.terracotta.angela.agent.com.Executor;
import org.terracotta.angela.agent.com.grid.hazelcast.HazelcastSshRemoteExecutor;
import org.terracotta.angela.client.AngelaOrchestrator;
import org.terracotta.angela.common.distribution.Distribution;
import org.terracotta.angela.common.distribution.RuntimeOption;
import org.terracotta.angela.common.net.DefaultPortAllocator;
import org.terracotta.angela.common.net.PortAllocator;
import org.terracotta.angela.common.util.IpUtils;
import org.terracotta.angela.common.util.OS;
import org.terracotta.angela.util.SshServer;
import org.terracotta.angela.util.Versions;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static java.util.Objects.requireNonNull;
import static org.junit.runners.Parameterized.Parameters;
import static org.terracotta.angela.common.distribution.Distribution.distribution;
import static org.terracotta.angela.common.topology.LicenseType.TERRACOTTA_OS;
import static org.terracotta.angela.common.topology.PackageType.KIT;
import static org.terracotta.angela.common.topology.Version.version;

/**
 * This base class will execute all tests of sub-classes in all the angela supported modes,
 * covering both Ignite and Hazelcast grid providers.
 */
@RunWith(Parameterized.class)
public abstract class BaseIT {
  @Parameters(name = "{index}: mode={0} hostname={1} inline={2} ssh={3}")
  public static Iterable<Object[]> data() {
    List<Object[]> cases = new ArrayList<>(10);

    cases.add(new Object[]{"igniteFree()", IpUtils.getHostName(), true, false});
    cases.add(new Object[]{"igniteFree()", IpUtils.getHostName(), false, false});
    cases.add(new Object[]{"igniteLocal()", IpUtils.getHostName(), true, false});
    cases.add(new Object[]{"igniteLocal()", IpUtils.getHostName(), false, false});
    cases.add(new Object[]{"hazelcastLocal()", IpUtils.getHostName(), true, false});
    cases.add(new Object[]{"hazelcastLocal()", IpUtils.getHostName(), false, false});

    if (!System.getProperty("java.version").startsWith("1.8") && !OS.INSTANCE.isWindows()) {
      // ssh tests only on 1.11 since they require the usage of -Djdk.net.hosts.file
      cases.add(new Object[]{"igniteRemote()", "testhostname", true, true});
      cases.add(new Object[]{"igniteRemote()", "testhostname", false, true});
      cases.add(new Object[]{"hazelcastRemote()", "testhostname", true, true});
      cases.add(new Object[]{"hazelcastRemote()", "testhostname", false, true});
    }

    return cases;
  }

  @Rule
  public Timeout timeout = Timeout.builder().withTimeout(4, TimeUnit.MINUTES).build();

  protected final transient PortAllocator portAllocator = new DefaultPortAllocator();
  protected final transient SshServer sshServer;
  protected final transient AngelaOrchestrator angelaOrchestrator;
  protected final transient Executor executor;

  protected final String hostname;
  protected final boolean inline;
  protected final RuntimeOption[] runtimeOptions;
  protected final AgentID agentID;

  protected BaseIT(String mode, String hostname, boolean inline, boolean ssh) {
    this.hostname = hostname;
    this.inline = inline;
    this.runtimeOptions = inline ? new RuntimeOption[]{RuntimeOption.INLINE_SERVERS} : new RuntimeOption[0];
    this.sshServer = !ssh ? null : new SshServer(Paths.get("target", "sshd", UUID.randomUUID().toString()))
        .withPort(portAllocator.reserve(1).next())
        .start();
    switch (mode) {
      case "igniteFree()":
        this.angelaOrchestrator = AngelaOrchestrator.builder()
            .withPortAllocator(portAllocator)
            .igniteFree()
            .build();
        break;
      case "igniteLocal()":
        this.angelaOrchestrator = AngelaOrchestrator.builder()
            .withPortAllocator(portAllocator)
            .igniteLocal()
            .build();
        break;
      case "igniteRemote()":
        this.angelaOrchestrator = AngelaOrchestrator.builder()
            .withPortAllocator(portAllocator)
            .igniteRemote(igniteSshRemoteExecutor -> igniteSshRemoteExecutor.setPort(requireNonNull(sshServer).getPort()))
            .build();
        break;
      case "hazelcastLocal()":
        this.angelaOrchestrator = AngelaOrchestrator.builder()
            .withPortAllocator(portAllocator)
            .hazelcastLocal()
            .build();
        break;
      case "hazelcastRemote()":
        this.angelaOrchestrator = AngelaOrchestrator.builder()
            .withPortAllocator(portAllocator)
            .hazelcastRemote((HazelcastSshRemoteExecutor hazelcastSshRemoteExecutor) -> hazelcastSshRemoteExecutor.setPort(requireNonNull(sshServer).getPort()))
            .build();
        break;
      default:
        throw new AssertionError(mode);
    }
    this.executor = angelaOrchestrator.getExecutor();
    this.agentID = executor.getLocalAgentID();
  }

  @After
  public void close() throws Exception {
    IOUtils.closeQuietly(angelaOrchestrator, sshServer, portAllocator);
  }

  /**
   * Old Ehcache version based on XML config
   */
  protected Distribution getOldDistribution() {
    return distribution(version(Versions.EHCACHE_VERSION_XML), KIT, TERRACOTTA_OS, runtimeOptions);
  }

  /**
   * New Ehcache version, based on dynamic config
   */
  protected Distribution getDistribution() {
    return distribution(version(Versions.EHCACHE_VERSION_DC), KIT, TERRACOTTA_OS, runtimeOptions);
  }
}
