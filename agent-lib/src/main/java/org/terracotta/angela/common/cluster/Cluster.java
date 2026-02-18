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
package org.terracotta.angela.common.cluster;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.terracotta.angela.agent.com.AgentID;
import org.terracotta.angela.agent.com.grid.ClusterPrimitives;
import org.terracotta.angela.common.clientconfig.ClientId;

import java.io.Serializable;

import static java.util.Objects.requireNonNull;

public class Cluster implements Serializable {
  private static final long serialVersionUID = 1L;

  @SuppressFBWarnings("SE_BAD_FIELD")
  private final ClusterPrimitives clusterPrimitives;
  private final AgentID from;
  private final ClientId clientId;

  public Cluster(ClusterPrimitives clusterPrimitives, AgentID from, ClientId clientId) {
    this.clusterPrimitives = requireNonNull(clusterPrimitives);
    this.from = requireNonNull(from);
    this.clientId = clientId;
  }

  public Barrier barrier(String name, int count) {
    return clusterPrimitives.barrier(name, count);
  }

  public AtomicCounter atomicCounter(String name, long initialValue) {
    return clusterPrimitives.atomicCounter(name, initialValue);
  }

  public AtomicBoolean atomicBoolean(String name, boolean initialValue) {
    return clusterPrimitives.atomicBoolean(name, initialValue);
  }

  public <T> AtomicReference<T> atomicReference(String name, T initialValue) {
    return clusterPrimitives.atomicReference(name, initialValue);
  }

  /**
   * @return the client ID if called in the context of a client job,
   * and null otherwise.
   */
  public ClientId getClientId() {
    return clientId;
  }

  /**
   * The agent from which the closure was executed.
   */
  public AgentID getFromAgentId() {
    return from;
  }
}
