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

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteAtomicLong;
import org.terracotta.angela.common.cluster.AtomicCounter;

class IgniteAtomicCounter implements AtomicCounter {
  private static final long serialVersionUID = 1L;

  private final String name;
  @SuppressFBWarnings("SE_BAD_FIELD")
  private final IgniteAtomicLong delegate;

  IgniteAtomicCounter(Ignite ignite, String name, long initVal) {
    this.name = name;
    this.delegate = ignite.atomicLong("Atomic-Counter-" + name, initVal, true);
  }

  @Override
  public long incrementAndGet() {
    return delegate.incrementAndGet();
  }

  @Override
  public long getAndIncrement() {
    return delegate.getAndIncrement();
  }

  @Override
  public long get() {
    return delegate.get();
  }

  @Override
  public long getAndSet(long value) {
    return delegate.getAndSet(value);
  }

  @Override
  public boolean compareAndSet(long expVal, long newVal) {
    return delegate.compareAndSet(expVal, newVal);
  }

  @Override
  public String toString() {
    return name + ":" + get();
  }
}
