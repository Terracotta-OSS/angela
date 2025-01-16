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
package org.terracotta.angela.common.net;

import java.io.Closeable;
import java.util.Iterator;
import java.util.stream.IntStream;
import java.util.stream.StreamSupport;

import static java.util.Spliterator.ORDERED;
import static java.util.Spliterators.spliteratorUnknownSize;

/**
 * @author Mathieu Carbou
 */
public interface PortAllocator extends Closeable {

  PortReservation reserve(int portCounts);

  @Override
  void close();

  interface PortReservation extends AutoCloseable, Iterator<Integer> {
    @Override
    void close();

    default IntStream stream() {
      return StreamSupport.stream(spliteratorUnknownSize(this, ORDERED), false).mapToInt(Integer::intValue);
    }
  }
}
