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
import com.hazelcast.cp.IAtomicReference;
import com.hazelcast.cp.lock.FencedLock;
import org.terracotta.angela.agent.com.grid.GridInitializer;

import java.util.Objects;

/**
 * Hazelcast implementation of {@link GridInitializer} that uses a CP {@link FencedLock}
 * and a CP {@link IAtomicReference} for cluster-wide one-time initialization.
 */
public class HazelcastGridInitializer implements GridInitializer {

  private final HazelcastInstance hz;

  public HazelcastGridInitializer(HazelcastInstance hz) {
    this.hz = Objects.requireNonNull(hz, "hz");
  }

  @Override
  public void initializeOnce(String name, Runnable initializer) {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(initializer, "initializer");

    FencedLock lock = hz.getCPSubsystem().getLock(name + "@init-lock");
    lock.lock();
    try {
      IAtomicReference<Boolean> initialized = hz.getCPSubsystem().getAtomicReference(name + "@initialized");
      if (Boolean.TRUE.equals(initialized.get())) {
        return;
      }
      initializer.run();
      initialized.set(Boolean.TRUE);
    } finally {
      lock.unlock();
    }
  }
}
