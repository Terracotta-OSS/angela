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
package org.terracotta.angela.agent.com;

import org.terracotta.angela.common.util.IpUtils;
import org.terracotta.angela.common.util.Pids;

import java.io.Serializable;
import java.net.InetSocketAddress;
import java.util.Objects;

import static java.util.Objects.requireNonNull;

/**
 * @author Mathieu Carbou
 */
public class AgentID implements Serializable {
  private static final long serialVersionUID = 1L;

  private static final AgentID LOCAL = new AgentID("local", IpUtils.getHostName(), 0, Pids.current());

  private final String name;
  private final String hostname;
  private final int port;
  private final long pid;

  public AgentID(String name, String hostname, int port, long pid) {
    this.name = requireNonNull(name);
    this.hostname = requireNonNull(hostname);
    this.port = port;
    this.pid = pid;
    if (name.contains("@") || name.contains("#") || name.contains(":")) {
      throw new IllegalArgumentException("Invalid characters in name: @ # :");
    }
    if (hostname.contains("@") || hostname.contains("#") || hostname.contains(":")) {
      throw new IllegalArgumentException("Invalid characters in hostname: @ # :");
    }
  }

  public long getPid() {
    return pid;
  }

  public String getName() {
    return name;
  }

  public String getHostName() {
    return hostname;
  }

  public int getPort() {
    return port;
  }

  public InetSocketAddress getAddress() {
    return InetSocketAddress.createUnresolved(hostname, port);
  }

  public boolean isLocal() {
    return port == 0 && name.equals("local");
  }

  public String getNodeName() {
    return name + "#" + pid + "@" + hostname + "#" + port;
  }

  @Override
  public String toString() {
    return getNodeName();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof AgentID)) return false;
    AgentID agentID = (AgentID) o;
    return getPort() == agentID.getPort() && getPid() == agentID.getPid() && getName().equals(agentID.getName()) && getHostName().equals(agentID.getHostName());
  }

  @Override
  public int hashCode() {
    return Objects.hash(getName(), getHostName(), getPort(), getPid());
  }

  public static AgentID valueOf(String s) {
    try {
      int p1 = s.indexOf("#");
      int p2 = s.indexOf("@", p1);
      int p3 = s.indexOf("#", p2);

      String instanceName = s.substring(0, p1);
      int pid = Integer.parseInt(s.substring(p1 + 1, p2));
      String hostname = s.substring(p2 + 1, p3);
      int port = Integer.parseInt(s.substring(p3 + 1));

      return new AgentID(instanceName, hostname, port, pid);
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("Invalid AgentID: " + s, e);
    }
  }

  public static AgentID local() {
    return LOCAL;
  }
}
