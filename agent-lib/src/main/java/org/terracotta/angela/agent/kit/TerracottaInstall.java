/*
 * The contents of this file are subject to the Terracotta Public License Version
 * 2.0 (the "License"); You may not use this file except in compliance with the
 * License. You may obtain a copy of the License at
 *
 * http://terracotta.org/legal/terracotta-public-license.
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License for
 * the specific language governing rights and limitations under the License.
 *
 * The Covered Software is Angela.
 *
 * The Initial Developer of the Covered Software is
 * Terracotta, Inc., a Software AG company
 */

package org.terracotta.angela.agent.kit;

import org.terracotta.angela.common.TerracottaServerInstance;
import org.terracotta.angela.common.distribution.Distribution;
import org.terracotta.angela.common.tcconfig.License;
import org.terracotta.angela.common.tcconfig.TerracottaServer;
import org.terracotta.angela.common.topology.Topology;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;


/**
 * Installation instance of a Terracotta server
 */
public class TerracottaInstall {

  private final File rootInstallLocation;
  private final Map<UUID, TerracottaServerInstance> terracottaServerInstances = new HashMap<>();

  public TerracottaInstall(File rootInstallLocation) {
    this.rootInstallLocation = rootInstallLocation;
  }

  public TerracottaServerInstance getTerracottaServerInstance(TerracottaServer terracottaServer) {
    synchronized (terracottaServerInstances) {
      return terracottaServerInstances.get(terracottaServer.getId());
    }
  }

  public File getInstallLocation(TerracottaServer terracottaServer) {
    synchronized (terracottaServerInstances) {
      return terracottaServerInstances.get(terracottaServer.getId()).getInstallLocation();
    }
  }

  public File getLicenseFileLocation(TerracottaServer terracottaServer) {
    synchronized (terracottaServerInstances) {
      return terracottaServerInstances.get(terracottaServer.getId()).getLicenseFileLocation();
    }
  }

  public void addServer(TerracottaServer terracottaServer, File installLocation, License license, Distribution distribution, Topology topology) {
    synchronized (terracottaServerInstances) {
      TerracottaServerInstance serverInstance = new TerracottaServerInstance(terracottaServer, installLocation, license, distribution, topology);
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

  public boolean installed(Distribution distribution) {
    synchronized (terracottaServerInstances) {
      return terracottaServerInstances.values().stream().anyMatch(tsi -> tsi.getDistribution().equals(distribution));
    }
  }

  public File installLocation(Distribution distribution) {
    synchronized (terracottaServerInstances) {
      TerracottaServerInstance terracottaServerInstance = terracottaServerInstances.values().stream()
          .filter(tsi -> tsi.getDistribution().equals(distribution))
          .findFirst()
          .orElseThrow(() -> new RuntimeException("Distribution not installed : " + distribution));
      return terracottaServerInstance.getInstallLocation();
    }
  }

  public File getRootInstallLocation() {
    return rootInstallLocation;
  }
}
