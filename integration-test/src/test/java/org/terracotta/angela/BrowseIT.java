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
import org.terracotta.angela.client.ClusterFactory;
import org.terracotta.angela.client.Tsa;
import org.terracotta.angela.client.config.ConfigurationContext;
import org.terracotta.angela.client.config.custom.CustomConfigurationContext;
import org.terracotta.angela.client.filesystem.RemoteFile;
import org.terracotta.angela.client.filesystem.RemoteFolder;
import org.terracotta.angela.common.tcconfig.TerracottaServer;
import org.terracotta.angela.common.topology.ClientArrayTopology;
import org.terracotta.angela.common.topology.Topology;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeFalse;
import static org.terracotta.angela.common.clientconfig.ClientArrayConfig.newClientArrayConfig;
import static org.terracotta.angela.common.tcconfig.TcConfig.tcConfig;
import static org.terracotta.angela.common.topology.Version.version;
import static org.terracotta.angela.util.TestUtils.TC_CONFIG_AP;
import static org.terracotta.angela.util.Versions.EHCACHE_VERSION_XML;

/**
 * @author Ludovic Orban
 */
public class BrowseIT extends BaseIT {

  public BrowseIT(String mode, String hostname, boolean inline, boolean ssh) {
    super(mode, hostname, inline, ssh);
  }

  @Test
  public void testClient() throws Exception {
    assumeFalse("Cannot run without Ignite", agentID.isLocal());

    ConfigurationContext configContext = CustomConfigurationContext.customConfigurationContext()
        .clientArray(clientArray -> clientArray
            .clientArrayTopology(new ClientArrayTopology(getOldDistribution(), newClientArrayConfig().host("foo", hostname)))
        );
    try (ClusterFactory factory = angelaOrchestrator.newClusterFactory("BrowseTest::testClient", configContext)) {
      ClientArray clientArray = factory.clientArray(0);
      Client client = clientArray.getClients().stream().findFirst().get();

      String msg = "hello, world!";
      Path fileToUpload = Paths.get("target/toUpload/uploaded-data.txt");
      Files.createDirectories(fileToUpload.getParent());
      Files.write(fileToUpload, msg.getBytes(StandardCharsets.UTF_8));

      client.browse("uploaded").upload(Paths.get("target/toUpload"));

      clientArray.executeOnAll(cluster -> {
        assertThat(new String(Files.readAllBytes(Paths.get("uploaded/uploaded-data.txt")), StandardCharsets.UTF_8), is(equalTo(msg)));
        Path fileToDownload = Paths.get("toDownload/downloaded-data.txt");
        Files.createDirectories(fileToDownload.getParent());
        Files.write(fileToDownload, msg.getBytes(StandardCharsets.UTF_8));
      }).get();

      client.browse("toDownload").list()
          .stream()
          .filter(remoteFile -> remoteFile.getName().equals("downloaded-data.txt"))
          .findAny()
          .get()
          .downloadTo(Paths.get("target/downloaded-data.txt"));

      assertThat(new String(Files.readAllBytes(Paths.get("target/downloaded-data.txt")), StandardCharsets.UTF_8), is(equalTo(msg)));
    }
  }

  @Test
  public void testUploadPlugin() throws Exception {
    ConfigurationContext configContext = CustomConfigurationContext.customConfigurationContext()
        .tsa(tsa -> tsa.topology(new Topology(getOldDistribution(), tcConfig(version(EHCACHE_VERSION_XML), TC_CONFIG_AP))));

    try (ClusterFactory factory = angelaOrchestrator.newClusterFactory("BrowseTest::testUploadPlugin", configContext)) {
      Tsa tsa = factory.tsa();
      tsa.uploadPlugin(new File(getClass().getResource("/keep-this-file-empty.txt").toURI()).toPath());

      for (TerracottaServer server : tsa.getTsaConfigurationContext().getTopology().getServers()) {
        RemoteFolder remoteFolder = tsa.browseFromKitLocation(server, "server/plugins/lib");
        Optional<RemoteFile> remoteFile = remoteFolder.list().stream().filter(f -> f.getName().equals("keep-this-file-empty.txt")).findFirst();
        assertThat(remoteFile.isPresent(), is(true));
      }
    }
  }

  @Test
  public void testNonExistentFolder() throws Exception {
    ConfigurationContext configContext = CustomConfigurationContext.customConfigurationContext()
        .clientArray(clientArray -> clientArray
            .clientArrayTopology(new ClientArrayTopology(getOldDistribution(), newClientArrayConfig().host("foo", hostname)))
        );

    try (ClusterFactory factory = angelaOrchestrator.newClusterFactory("BrowseTest::testNonExistentFolder", configContext)) {
      ClientArray clientArray = factory.clientArray(0);
      try {
        Client localhost = clientArray.getClients().stream().findFirst().get();
        localhost.browse("target/does/not/exist").downloadTo(Paths.get("target/destination"));
        fail("expected IOException");
      } catch (Exception e) {
        // expected
      }
    }
  }

  @Test
  public void testUpload() throws Exception {
    ConfigurationContext configContext = CustomConfigurationContext.customConfigurationContext()
        .clientArray(clientArray -> clientArray
            .clientArrayTopology(new ClientArrayTopology(getOldDistribution(), newClientArrayConfig().host("foo", hostname)))
        );

    try (ClusterFactory factory = angelaOrchestrator.newClusterFactory("BrowseTest::testUpload", configContext)) {
      ClientArray clientArray = factory.clientArray(0);
      Client localhost = clientArray.getClients().stream().findFirst().get();
      RemoteFolder folder = localhost.browse("target/does-not-exist"); // check that we can upload to non-existent folder & the folder will be created

      folder.upload("keep-this-file-empty.txt", getClass().getResource("/keep-this-file-empty.txt"));

      Optional<RemoteFile> createdFolder = localhost.browse("target").list().stream().filter(remoteFile -> remoteFile.getName().equals("does-not-exist") && remoteFile.isFolder()).findAny();
      assertThat(createdFolder.isPresent(), is(true));

      List<RemoteFile> remoteFiles = ((RemoteFolder) createdFolder.get()).list();
      Optional<RemoteFile> remoteFileOpt = remoteFiles.stream().filter(remoteFile -> remoteFile.getName().equals("keep-this-file-empty.txt")).findAny();
      assertThat(remoteFileOpt.isPresent(), is(true));

      // Close explicitly so cleanup errors fail the test instead of being logged by ClusterFactory.close().
      localhost.close();
      assertThat(executor.getGroup().contains(executor.getAgentID(hostname)), is(true));
    }
  }
}
