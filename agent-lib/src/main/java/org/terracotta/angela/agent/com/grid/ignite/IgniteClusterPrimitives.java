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
package org.terracotta.angela.agent.com.grid.ignite;

import org.apache.ignite.Ignite;
import org.terracotta.angela.agent.com.grid.ClusterPrimitives;
import org.terracotta.angela.common.cluster.AtomicBoolean;
import org.terracotta.angela.common.cluster.AtomicCounter;
import org.terracotta.angela.common.cluster.AtomicReference;
import org.terracotta.angela.common.cluster.Barrier;

import java.util.Objects;

/**
 * Ignite-backed implementation of {@link ClusterPrimitives}.
 */
public class IgniteClusterPrimitives implements ClusterPrimitives {

  private final Ignite ignite;

  public IgniteClusterPrimitives(Ignite ignite) {
    this.ignite = Objects.requireNonNull(ignite);
  }

  @Override
  public AtomicCounter atomicCounter(String name, long initialValue) {
    return new IgniteAtomicCounter(ignite, name, initialValue);
  }

  @Override
  public AtomicBoolean atomicBoolean(String name, boolean initialValue) {
    return new IgniteAtomicBoolean(ignite, name, initialValue);
  }

  @Override
  public <T> AtomicReference<T> atomicReference(String name, T initialValue) {
    return new IgniteAtomicReference<>(ignite, name, initialValue);
  }

  @Override
  public Barrier barrier(String name, int count) {
    return new IgniteBarrier(ignite, count, name);
  }
}
