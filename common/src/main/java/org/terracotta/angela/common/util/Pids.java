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

/**
 * Lightweight helpers around {@link ProcessHandle} so we do not depend on
 * Zeroturnaround's PID utilities (which rely on sun.* internals and trigger
 * reflective-access warnings on newer JVMs).
 */
public final class Pids {

  private Pids() {
  }

  public static long of(Process process) {
    return process.pid();
  }

  public static long current() {
    return ProcessHandle.current().pid();
  }

}
