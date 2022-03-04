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
package org.terracotta.angela.common.distribution;

import org.jetbrains.annotations.NotNull;
import org.terracotta.angela.common.util.ActivityTracker;

import java.io.IOException;
import java.io.OutputStream;

/**
 * @author Mathieu Carbou
 */
public class TrackedOutputStream extends OutputStream {
  private final ActivityTracker activityTracker;
  private final OutputStream delegate;

  public TrackedOutputStream(ActivityTracker activityTracker, OutputStream delegate) {
    this.activityTracker = activityTracker;
    this.delegate = delegate;
  }

  public ActivityTracker getActivityTracker() {
    return activityTracker;
  }

  @Override
  public void write(int b) throws IOException {
    activityTracker.touch();
    delegate.write(b);
  }

  @Override
  public void write(@NotNull byte[] b) throws IOException {
    activityTracker.touch();
    delegate.write(b);
  }

  @Override
  public void write(@NotNull byte[] b, int off, int len) throws IOException {
    activityTracker.touch();
    delegate.write(b, off, len);
  }

  @Override
  public void flush() throws IOException {delegate.flush();}

  @Override
  public void close() throws IOException {delegate.close();}
}
