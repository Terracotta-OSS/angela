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
import com.hazelcast.cp.IAtomicLong;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.terracotta.angela.common.cluster.AtomicBoolean;

class HazelcastAtomicBoolean implements AtomicBoolean {
  private static final long serialVersionUID = 1L;

  private final String name;
  @SuppressFBWarnings("SE_BAD_FIELD")
  private final IAtomicLong delegate;

  HazelcastAtomicBoolean(HazelcastInstance hz, String name) {
    this.name = name;
    this.delegate = hz.getCPSubsystem().getAtomicLong("Atomic-Boolean-" + name);
  }

  @Override
  public boolean get() {
    return delegate.get() != 0L;
  }

  @Override
  public void set(boolean value) {
    delegate.getAndSet(value ? 1L : 0L);
  }

  @Override
  public boolean getAndSet(boolean value) {
    return delegate.getAndSet(value ? 1L : 0L) != 0L;
  }

  @Override
  public boolean compareAndSet(boolean expVal, boolean newVal) {
    return delegate.compareAndSet(expVal ? 1L : 0L, newVal ? 1L : 0L);
  }

  @Override
  public String toString() {
    return name + ":" + get();
  }
}
