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
package org.terracotta.angela.agent.com.grid.hazelcast;

import com.hazelcast.core.HazelcastInstance;
import org.terracotta.angela.agent.com.grid.ClusterPrimitives;
import org.terracotta.angela.common.cluster.AtomicBoolean;
import org.terracotta.angela.common.cluster.AtomicCounter;
import org.terracotta.angela.common.cluster.AtomicReference;
import org.terracotta.angela.common.cluster.Barrier;

import java.util.Objects;

/**
 * Hazelcast-backed implementation of {@link ClusterPrimitives}.
 * <p>
 * Initial values for distributed primitives are applied exactly once cluster-wide using
 * {@link HazelcastGridInitializer}, which prevents accidental resets of legitimately-zero
 * or legitimately-null values when the same primitive is constructed on multiple nodes.
 */
public class HazelcastClusterPrimitives implements ClusterPrimitives {

  private final HazelcastInstance hz;
  private final HazelcastGridInitializer initializer;

  public HazelcastClusterPrimitives(HazelcastInstance hz) {
    this.hz = Objects.requireNonNull(hz);
    this.initializer = new HazelcastGridInitializer(hz);
  }

  @Override
  public AtomicCounter atomicCounter(String name, long initialValue) {
    initializer.initializeOnce("Atomic-Counter-" + name,
        () -> hz.getCPSubsystem().getAtomicLong("Atomic-Counter-" + name).set(initialValue));
    return new HazelcastAtomicCounter(hz, name);
  }

  @Override
  public AtomicBoolean atomicBoolean(String name, boolean initialValue) {
    initializer.initializeOnce("Atomic-Boolean-" + name,
        () -> hz.getCPSubsystem().getAtomicLong("Atomic-Boolean-" + name).set(initialValue ? 1L : 0L));
    return new HazelcastAtomicBoolean(hz, name);
  }

  @Override
  public <T> AtomicReference<T> atomicReference(String name, T initialValue) {
    initializer.initializeOnce("Atomic-Reference-" + name,
        () -> hz.getCPSubsystem().getAtomicReference("Atomic-Reference-" + name).set(initialValue));
    return new HazelcastAtomicReference<>(hz, name);
  }

  @Override
  public Barrier barrier(String name, int count) {
    return new HazelcastBarrier(hz, count, name);
  }
}
