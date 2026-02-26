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
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.terracotta.angela.common.cluster.AtomicReference;

class HazelcastAtomicReference<T> implements AtomicReference<T> {
  private static final long serialVersionUID = 1L;

  @SuppressFBWarnings("SE_BAD_FIELD")
  private final IAtomicReference<T> delegate;

  HazelcastAtomicReference(HazelcastInstance hz, String name) {
    this.delegate = hz.getCPSubsystem().getAtomicReference("Atomic-Reference-" + name);
  }

  @Override
  public void set(T value) {
    delegate.set(value);
  }

  @Override
  public boolean compareAndSet(T expect, T update) {
    return delegate.compareAndSet(expect, update);
  }

  @Override
  public T get() {
    return delegate.get();
  }
}
