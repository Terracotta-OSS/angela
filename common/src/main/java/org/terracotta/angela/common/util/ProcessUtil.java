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
package org.terracotta.angela.common.util;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

public class ProcessUtil {

  public static boolean destroyGracefullyOrForcefullyAndWait(long pid) {
    return destroyGracefullyOrForcefullyAndWait(pid, 30, TimeUnit.SECONDS, 10, TimeUnit.SECONDS);
  }

  public static boolean destroyGracefullyOrForcefullyAndWait(
          long pid,
          long gracefulTimeout, TimeUnit gracefulUnit,
          long forcefulTimeout, TimeUnit forcefulUnit
  ) {
    Duration graceful = Duration.ofMillis(gracefulUnit.toMillis(gracefulTimeout));
    Duration forceful = Duration.ofMillis(forcefulUnit.toMillis(forcefulTimeout));

    ProcessHandle handle = ProcessHandle.of(pid).orElse(null);
    if (handle == null || !handle.isAlive()) {
      // Process already dead or doesn't exist
      return true;
    }

    boolean isWindows = OS.INSTANCE.isWindows();
    boolean isCurrentProcess = pid == ProcessHandle.current().pid();

    // Attempt graceful shutdown
    try {
      if (!tryGracefulKill(pid, isWindows)) {
        // On Windows, cannot call destroy() on current process
        if (!isCurrentProcess) {
          try {
            handle.destroy();
          } catch (IllegalStateException e) {
            // On Windows, ProcessHandle.destroy() throws IllegalStateException for current process
            // This shouldn't happen since we check isCurrentProcess, but catch it just in case
          }
        }
      }
    } catch (IOException | InterruptedException e) {
      // Fallback to handle.destroy() if command execution fails
      if (!isCurrentProcess) {
        try {
          handle.destroy();
        } catch (IllegalStateException ex) {
          // On Windows, ProcessHandle.destroy() throws IllegalStateException for current process
        }
      }
    }

    if (waitUntilNotAlive(handle, graceful)) {
      return true;
    }

    // Graceful shutdown failed, attempt forceful kill
    try {
      if (!tryForcefulKill(pid, isWindows)) {
        // On Windows, cannot call destroyForcibly() on current process
        if (!isCurrentProcess) {
          try {
            handle.destroyForcibly();
          } catch (IllegalStateException e) {
            // On Windows, ProcessHandle.destroyForcibly() throws IllegalStateException for current process
            // This shouldn't happen since we check isCurrentProcess, but catch it just in case
          }
        }
      }
    } catch (IOException | InterruptedException e) {
      // Fallback to handle.destroyForcibly() if command execution fails
      if (!isCurrentProcess) {
        try {
          handle.destroyForcibly();
        } catch (IllegalStateException ex) {
          // On Windows, ProcessHandle.destroyForcibly() throws IllegalStateException for current process
        }
      }
    }

    return waitUntilNotAlive(handle, forceful);
  }

  private static boolean tryGracefulKill(long pid, boolean isWindows) throws IOException, InterruptedException {
    if (isWindows) {
      // No /F → try to close; /T includes child processes
      return runCommand(10, TimeUnit.SECONDS, "taskkill", "/PID", String.valueOf(pid), "/T") == 0;
    } else {
      // TERM is the polite signal
      return runCommand(5, TimeUnit.SECONDS, "kill", "-TERM", String.valueOf(pid)) == 0;
    }
  }

  private static boolean tryForcefulKill(long pid, boolean isWindows) throws IOException, InterruptedException {
    if (isWindows) {
      // /F → force; /T → process tree
      return runCommand(10, TimeUnit.SECONDS, "taskkill", "/F", "/T", "/PID", String.valueOf(pid)) == 0;
    } else {
      return runCommand(5, TimeUnit.SECONDS, "kill", "-KILL", String.valueOf(pid)) == 0;
    }
  }

  private static boolean waitUntilNotAlive(ProcessHandle handle, Duration timeout) {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      if (!handle.isAlive()) return true;
      try {
        Thread.sleep(50);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    // Final check in case it died right at the end
    return !handle.isAlive();
  }

  private static int runCommand(long timeout, TimeUnit unit, String... cmd)
          throws IOException, InterruptedException {
    Process p = new ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .start();
    boolean finished = p.waitFor(timeout, unit);
    if (!finished) {
      p.destroyForcibly();
      return 124;
    }
    return p.exitValue();
  }
}
