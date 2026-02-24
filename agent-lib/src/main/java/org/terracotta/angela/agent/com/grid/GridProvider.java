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
package org.terracotta.angela.agent.com.grid;

import org.terracotta.angela.agent.com.AgentID;
import org.terracotta.angela.agent.com.Executor;

import java.util.UUID;

/**
 * Entry point for a grid backend. Implementations start or join the grid and expose
 * the two main services: job execution and distributed coordination primitives.
 */
public interface GridProvider extends AutoCloseable {
  /**
   * Returns the {@link AgentID} assigned to this node when it joined the grid.
   */
  AgentID getAgentID();

  /**
   * Start or join the grid and return an {@link Executor} scoped to the given group.
   */
  Executor createExecutor(UUID group, AgentID agentID);

  /**
   * Create a {@link ClusterPrimitives} instance for distributed coordination.
   */
  ClusterPrimitives createClusterPrimitives();

  @Override
  void close();
}
