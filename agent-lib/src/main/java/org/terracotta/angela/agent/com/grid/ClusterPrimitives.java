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

import org.terracotta.angela.common.cluster.AtomicBoolean;
import org.terracotta.angela.common.cluster.AtomicCounter;
import org.terracotta.angela.common.cluster.AtomicReference;
import org.terracotta.angela.common.cluster.Barrier;

/**
 * Abstraction over distributed coordination primitives provided by the underlying grid.
 */
public interface ClusterPrimitives {
  AtomicCounter atomicCounter(String name, long initialValue);

  AtomicBoolean atomicBoolean(String name, boolean initialValue);

  <T> AtomicReference<T> atomicReference(String name, T initialValue);

  Barrier barrier(String name, int count);
}
