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
package org.terracotta.angela.common.net;

import org.terracotta.utilities.test.net.PortManager;

import java.util.Collection;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.CopyOnWriteArrayList;

import static java.util.Collections.emptyList;

/**
 * @author Mathieu Carbou
 */
public class DefaultPortAllocator implements PortAllocator {

  private final PortManager portManager = PortManager.getInstance();
  private final Collection<PortManager.PortRef> reservations = new CopyOnWriteArrayList<>();

  @Override
  public PortReservation reserve(int portCounts) {
    List<PortManager.PortRef> portRefs = portCounts < 1 ? emptyList() : portManager.reservePorts(portCounts);
    reservations.addAll(portRefs);
    return new PortReservation() {
      int i = 0;

      @Override
      public Integer next() {
        if (i >= portRefs.size()) {
          throw new NoSuchElementException();
        }
        return portRefs.get(i++).port();
      }

      @Override
      public boolean hasNext() {
        return i < portRefs.size();
      }

      @Override
      public void close() {
        for (PortManager.PortRef portRef : portRefs) {
          if (reservations.remove(portRef)) {
            portRef.close();
          }
        }
      }
    };
  }

  @Override
  public void close() {
    while (!reservations.isEmpty() && !Thread.currentThread().isInterrupted()) {
      for (PortManager.PortRef portRef : reservations) {
        if (reservations.remove(portRef)) {
          portRef.close();
        }
      }
    }
  }
}