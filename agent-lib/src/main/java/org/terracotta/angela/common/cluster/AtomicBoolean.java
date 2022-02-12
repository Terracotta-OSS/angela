/*
 * Copyright Terracotta, Inc.
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

import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteAtomicLong;

public class AtomicBoolean {

  private final String name;
  private final IgniteAtomicLong igniteCounter;

  AtomicBoolean(Ignite ignite, String name, boolean initVal) {
    this.name = name;
    igniteCounter = ignite.atomicLong("Atomic-Boolean-" + name, initVal ? 1L : 0L, true);
  }

  public boolean get() {
    return igniteCounter.get() != 0L;
  }

  public void set(boolean value) {
    igniteCounter.getAndSet(value ? 1L : 0L);
  }

  public boolean getAndSet(boolean value) {
    return igniteCounter.getAndSet(value ? 1L : 0L) != 0L;
  }

  public boolean compareAndSet(boolean expVal, boolean newVal) {
    return igniteCounter.compareAndSet(expVal ? 1L : 0L, newVal ? 1L : 0L);
  }

  @Override
  public String toString() {
    return name + ":" + get();
  }
}