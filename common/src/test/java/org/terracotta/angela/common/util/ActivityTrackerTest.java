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

import org.junit.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static java.lang.Thread.sleep;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

/**
 * @author Mathieu Carbou
 */
public class ActivityTrackerTest {

  @Test
  public void startStop() {
    try (ActivityTracker activityTracker = ActivityTracker.of(Duration.ofSeconds(1))) {
      assertFalse(activityTracker.isStarted());
      activityTracker.start();
      assertTrue(activityTracker.isStarted());
      activityTracker.stop();
      assertFalse(activityTracker.isStarted());
    }
  }

  @Test
  public void events() throws InterruptedException {
    try (ActivityTracker activityTracker = ActivityTracker.of(Duration.ofSeconds(1))) {
      AtomicInteger inactives = new AtomicInteger();
      AtomicInteger starts = new AtomicInteger();
      AtomicInteger stops = new AtomicInteger();

      activityTracker.onStart(starts::incrementAndGet);
      activityTracker.onStop(stops::incrementAndGet);
      activityTracker.onInactivity(inactives::incrementAndGet);

      // not started
      sleep(2_000);
      assertThat(inactives.get(), is(equalTo(0)));

      // on start
      activityTracker.start();
      assertThat(starts.get(), is(equalTo(1)));

      // inactive
      sleep(2_000);
      int now1 = inactives.get();
      assertTrue(now1 >= 1);

      // on stop
      activityTracker.stop();
      assertThat(stops.get(), is(equalTo(1)));

      // stopped
      sleep(2_000);
      int now2 = inactives.get();
      assertThat(now2, is(equalTo(now1)));

      // restart
      activityTracker.start();
      assertThat(starts.get(), is(equalTo(2)));

      // inactive again
      sleep(2_000);
      int now3 = inactives.get();
      assertTrue(now3 >= 1);

      // stopped again
      activityTracker.stop();
      assertThat(stops.get(), is(equalTo(2)));
    }
  }

  @Test
  public void stopOnInactivity() throws InterruptedException {
    try (ActivityTracker activityTracker = ActivityTracker.of(Duration.ofSeconds(1))) {
      AtomicInteger stops = new AtomicInteger();
      activityTracker.onStop(stops::incrementAndGet);
      activityTracker.onInactivity(activityTracker::stop);
      activityTracker.start();

      sleep(2_000);
      assertThat(stops.get(), is(equalTo(1)));
      assertFalse(activityTracker.isStarted());
    }
  }
}