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
package org.terracotta.angela.common.util;

import java.io.Closeable;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TransferQueue;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tracker of inactivity
 *
 * @author Mathieu Carbou
 */
public class ActivityTracker implements Closeable {

  private final Duration inactivityDelay;
  private final Map<String, Collection<Runnable>> listeners = new ConcurrentHashMap<>();
  private final TransferQueue<Boolean> activity = new LinkedTransferQueue<>();
  private final boolean enabled;
  private final AtomicReference<Thread> monitor = new AtomicReference<>();

  private ActivityTracker(Duration inactivityDelay) {
    this.enabled = !inactivityDelay.equals(Duration.ZERO);
    this.inactivityDelay = inactivityDelay;
  }


  public Duration getInactivityDelay() {
    return inactivityDelay;
  }

  @Override
  public void close() {
    stop();
  }

  public void onInactivity(Runnable runnable) {
    if (enabled) {
      getListeners("inactivity").add(runnable);
    }
  }

  public void onStart(Runnable runnable) {
    if (enabled) {
      getListeners("start").add(runnable);
    }
  }

  public void onStop(Runnable runnable) {
    if (enabled) {
      getListeners("stop").add(runnable);
    }
  }

  /**
   * Record any activity
   */
  public void touch() {
    if (enabled) {
      activity.tryTransfer(Boolean.TRUE);
    }
  }

  public boolean isStarted() {
    return monitor.get() != null;
  }

  public void stop() {
    internalStop()
        .filter(thread -> thread != Thread.currentThread())
        .ifPresent(thread -> {
          try {
            thread.join();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });
  }

  public void start() {
    if (enabled && !isStarted()) {
      CountDownLatch started = new CountDownLatch(1);
      Thread prev = monitor.getAndUpdate(thread -> {
        if (thread == null) {
          thread = new Thread(() -> {
            try {
              started.countDown();
              while (!Thread.currentThread().isInterrupted()) {
                try {
                  if (activity.poll(inactivityDelay.toMillis(), TimeUnit.MILLISECONDS) == null) {
                    // timeout and no new element => inactivity
                    getListeners("inactivity").forEach(Runnable::run);
                  }
                } catch (InterruptedException e) {
                  // stopTrackingInactivity() called
                  Thread.currentThread().interrupt();
                }
              }
            } finally {
              internalStop();
            }
          });
          thread.setName("ActivityTracker:" + inactivityDelay + ":" + thread.hashCode());
          thread.setDaemon(true);
          thread.start();
        }
        return thread;
      });
      if (prev == null) {
        try {
          started.await();
          getListeners("start").forEach(Runnable::run);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    }
  }

  private Optional<Thread> internalStop() {
    Thread prev = monitor.getAndUpdate(thread -> {
      if (thread != null) {
        thread.interrupt();
      }
      return null;
    });
    if (prev != null) {
      getListeners("stop").forEach(Runnable::run);
    }
    return Optional.ofNullable(prev);
  }

  private Collection<Runnable> getListeners(String event) {
    return listeners.compute(event, (e, listeners) -> listeners != null ? listeners : new CopyOnWriteArrayList<>());
  }

  public static ActivityTracker of(Duration inactivityKillerDelay) {
    return new ActivityTracker(inactivityKillerDelay);
  }
}
