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
package org.terracotta.angela.common.util;

import org.terracotta.angela.common.AngelaProperties;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;

public class IpUtils {

  private static final Collection<String> LOCAL_HOSTNAMES = new HashSet<>();
  private static final String LOCAL_HOSTNAME;

  // caches costly resolutions and also what angela has to consider being "local" in order to not trigger an SSH remote installation
  static {
    InetAddress localAddress = null;
    String hostname;
    try {
      localAddress = InetAddress.getLocalHost();
      hostname = localAddress.getHostName();
    } catch (UnknownHostException ex) {
      hostname = resolveFallbackHostname();
    }

    LOCAL_HOSTNAME = hostname;
    LOCAL_HOSTNAMES.add(LOCAL_HOSTNAME);
    if (localAddress != null) {
      LOCAL_HOSTNAMES.add(localAddress.getHostAddress());
    }
    LOCAL_HOSTNAMES.add("localhost");
    LOCAL_HOSTNAMES.add("127.0.0.1");
    LOCAL_HOSTNAMES.add("::1");
  }

  private static String resolveFallbackHostname() {
    String envHost = System.getenv("HOSTNAME");
    if (envHost != null && !envHost.trim().isEmpty()) {
      return envHost.trim();
    }
    envHost = System.getenv("COMPUTERNAME");
    if (envHost != null && !envHost.trim().isEmpty()) {
      return envHost.trim();
    }
    return "localhost";
  }

  /**
   * Try to determine (but not accurately) if a given name matches a local address.
   * A local address is a local IP or local hostname.
   * <p>
   * This "name" can be any Angela generated names (i.e. in case of client arrays instanceId or symbolic names),
   * we do not use "getByName()" to avoid any dns resolution
   */
  public static boolean isLocal(String name) {
    return LOCAL_HOSTNAMES.contains(name)
        || name.startsWith("169.254.")
        || name.startsWith("fe80:")
        || Arrays.asList(AngelaProperties.ADDED_LOCAL_HOSTNAMES.getValue().split(",")).contains(name);
  }

  public static boolean areAllLocal(Collection<String> targetServerNames) {
    for (String targetServerName : targetServerNames) {
      if (!isLocal(targetServerName)) {
        return false;
      }
    }
    return true;
  }

  public static String getHostName() {
    return LOCAL_HOSTNAME;
  }
}
