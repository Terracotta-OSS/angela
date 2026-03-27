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
import com.hazelcast.cp.ICountDownLatch;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.terracotta.angela.common.cluster.Barrier;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

class HazelcastBarrier implements Barrier {
  private static final long serialVersionUID = 1L;

  @SuppressFBWarnings("SE_BAD_FIELD")
  private final HazelcastInstance hz;
  private final int count;
  private final int index;
  private final String name;
  @SuppressFBWarnings("SE_BAD_FIELD")
  private volatile ICountDownLatch countDownLatch;
  private volatile int resetCount;

  HazelcastBarrier(HazelcastInstance hz, int count, String name) {
    this.hz = hz;
    this.count = count;
    // Assign a unique 0-based index to this participant using a distributed counter
    IAtomicLong indexCounter = hz.getCPSubsystem().getAtomicLong("Barrier-Counter-" + name);
    this.index = (int) indexCounter.getAndIncrement();
    // Reset the counter when all participants have registered (so the next group starts at 0)
    indexCounter.compareAndSet(count, 0);
    this.name = name;
    resetLatch();
  }

  private void resetLatch() {
    ICountDownLatch latch = hz.getCPSubsystem().getCountDownLatch("Barrier-" + name + "#" + (resetCount++));
    latch.trySetCount(count);
    this.countDownLatch = latch;
  }

  @Override
  public int await() {
    try {
      countDownLatch.countDown();
      // await() with very long timeout is equivalent to blocking indefinitely
      countDownLatch.await(Long.MAX_VALUE / 2, TimeUnit.MILLISECONDS);
      return index;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    } finally {
      resetLatch();
    }
  }

  @Override
  public int await(long time, TimeUnit unit) throws TimeoutException {
    try {
      countDownLatch.countDown();
      if (!countDownLatch.await(time, unit)) {
        throw new TimeoutException();
      }
      return index;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    } finally {
      resetLatch();
    }
  }
}
