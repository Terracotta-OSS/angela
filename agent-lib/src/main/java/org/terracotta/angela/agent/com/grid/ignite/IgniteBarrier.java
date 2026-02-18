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
import org.apache.ignite.IgniteCountDownLatch;
import org.terracotta.angela.common.cluster.Barrier;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

class IgniteBarrier implements Barrier {
  private static final long serialVersionUID = 1L;

  @SuppressFBWarnings("SE_BAD_FIELD")
  private final Ignite ignite;
  private final int count;
  private final int index;
  private final String name;
  @SuppressFBWarnings("SE_BAD_FIELD")
  private volatile IgniteCountDownLatch countDownLatch;
  private volatile int resetCount;

  IgniteBarrier(Ignite ignite, int count, String name) {
    this.ignite = ignite;
    this.count = count;
    IgniteAtomicLong igniteCounter = ignite.atomicLong("Barrier-Counter-" + name, 0, true);
    this.index = (int) igniteCounter.getAndIncrement();
    igniteCounter.compareAndSet(count, 0);
    this.name = name;
    resetLatch();
  }

  private void resetLatch() {
    countDownLatch = ignite.countDownLatch("Barrier-" + name + "#" + (resetCount++), count, true, true);
  }

  @Override
  public int await() {
    int countDown = countDownLatch.countDown();
    try {
      if (countDown > 0) {
        countDownLatch.await();
      }
      return index;
    } finally {
      resetLatch();
    }
  }

  @Override
  public int await(long time, TimeUnit unit) throws TimeoutException {
    int countDown = countDownLatch.countDown();
    try {
      if (countDown > 0) {
        if (!countDownLatch.await(time, unit)) {
          throw new TimeoutException();
        }
      }
      return index;
    } finally {
      resetLatch();
    }
  }
}
