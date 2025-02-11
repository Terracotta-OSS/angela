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
import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteAtomicLong;

import java.io.Serializable;

public class AtomicCounter implements Serializable {
  private static final long serialVersionUID = 1L;
  private final String name;
  @SuppressFBWarnings("SE_BAD_FIELD")
  private final IgniteAtomicLong igniteCounter;

  AtomicCounter(Ignite ignite, String name, long initVal) {
    this.name = name;
    igniteCounter = ignite.atomicLong("Atomic-Counter-" + name, initVal, true);
  }

  public long incrementAndGet() {
    return igniteCounter.incrementAndGet();
  }

  public long getAndIncrement() {
    return igniteCounter.getAndIncrement();
  }

  public long get() {
    return igniteCounter.get();
  }

  public long getAndSet(long value) {
    return igniteCounter.getAndSet(value);
  }

  public boolean compareAndSet(long expVal, long newVal) {
    return igniteCounter.compareAndSet(expVal, newVal);
  }

  @Override
  public String toString() {
    return name + ":" + get();
  }
}
