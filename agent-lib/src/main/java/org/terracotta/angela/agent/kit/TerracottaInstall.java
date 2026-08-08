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

import org.terracotta.angela.common.TerracottaServerInstance;
import org.terracotta.angela.common.distribution.Distribution;
import org.terracotta.angela.common.net.PortAllocator;
import org.terracotta.angela.common.tcconfig.License;
import org.terracotta.angela.common.tcconfig.TerracottaServer;
import org.terracotta.angela.common.topology.Topology;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;


/**
 * Installation instance of a Terracotta server
 */
public class TerracottaInstall {

  private final PortAllocator portAllocator;
  private final Map<UUID, TerracottaServerInstance> terracottaServerInstances = new HashMap<>();

  public TerracottaInstall(PortAllocator portAllocator) {
    this.portAllocator = portAllocator;
  }

  public TerracottaServerInstance getTerracottaServerInstance(TerracottaServer terracottaServer) {
    synchronized (terracottaServerInstances) {
      return terracottaServerInstances.get(terracottaServer.getId());
    }
  }

  public File getInstallLocation(TerracottaServer terracottaServer) {
    synchronized (terracottaServerInstances) {
      return terracottaServerInstances.get(terracottaServer.getId()).getWorkingDir();
    }
  }

  public File getKitLocation(TerracottaServer terracottaServer) {
    synchronized (terracottaServerInstances) {
      return terracottaServerInstances.get(terracottaServer.getId()).getKitDir();
    }
  }

  public File getLicenseFileLocation(TerracottaServer terracottaServer) {
    synchronized (terracottaServerInstances) {
      return terracottaServerInstances.get(terracottaServer.getId()).getLicenseFileLocation();
    }
  }

  public void addServer(TerracottaServer terracottaServer, File kitLocation, File installLocation, License license, Distribution distribution, Topology topology) {
    synchronized (terracottaServerInstances) {
      TerracottaServerInstance serverInstance = new TerracottaServerInstance(terracottaServer, kitLocation, installLocation, license, distribution, topology, portAllocator);
      terracottaServerInstances.put(terracottaServer.getId(), serverInstance);
    }
  }

  public int removeServer(TerracottaServer terracottaServer) {
    synchronized (terracottaServerInstances) {
      TerracottaServerInstance instance = terracottaServerInstances.remove(terracottaServer.getId());
      if (instance != null) {
        instance.close();
      }
      return terracottaServerInstances.size();
    }
  }

  public int terracottaServerInstanceCount() {
    synchronized (terracottaServerInstances) {
      return terracottaServerInstances.size();
    }
  }

  /**
   * Snapshot of every server instance currently installed on this agent. Used
   * to aggregate the per-(thisServer, peerServer) group-port table when
   * starting a server, since each instance's createDisruptionLinks records
   * only its own inbound proxy ports.
   */
  public Collection<TerracottaServerInstance> getAllTerracottaServerInstances() {
    synchronized (terracottaServerInstances) {
      return Collections.unmodifiableCollection(new ArrayList<>(terracottaServerInstances.values()));
    }
  }

  public boolean installed(Distribution distribution) {
    synchronized (terracottaServerInstances) {
      return terracottaServerInstances.values().stream().anyMatch(tsi -> tsi.getDistribution().equals(distribution));
    }
  }


  public File kitLocation(Distribution distribution) {
    synchronized (terracottaServerInstances) {
      TerracottaServerInstance terracottaServerInstance = terracottaServerInstances.values().stream()
          .filter(tsi -> tsi.getDistribution().equals(distribution))
          .findFirst()
          .orElseThrow(() -> new RuntimeException("Distribution not installed : " + distribution));
      return terracottaServerInstance.getKitDir();
    }
  }
}
