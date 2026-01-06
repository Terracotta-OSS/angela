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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.time.Instant;

/**
 * Utility to capture and log thread dumps without relying on external tooling.
 */
public final class ThreadDump {
  private static final Logger LOGGER = LoggerFactory.getLogger(ThreadDump.class);

  private ThreadDump() {
  }

  public static void dump(String reason) {
    dump(LOGGER, reason);
  }

  public static void dump(Logger logger, String reason) {
    ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
    ThreadInfo[] threads = threadMXBean.dumpAllThreads(true, true);
    StringBuilder sb = new StringBuilder();
    sb.append('\n')
        .append("================ Angela Thread dump: ")
        .append(reason)
        .append(" @ ")
        .append(Instant.now())
        .append(" ================\n");
    for (ThreadInfo info : threads) {
      appendThreadInfo(sb, info);
    }
    sb.append("================ End of thread dump ================\n");
    logger.warn(sb.toString());
  }

  private static void appendThreadInfo(StringBuilder sb, ThreadInfo info) {
    if (info == null) {
      return;
    }
    sb.append('\"')
        .append(info.getThreadName())
        .append("\" Id=")
        .append(info.getThreadId())
        .append(" State=")
        .append(info.getThreadState());
    if (info.getLockName() != null) {
      sb.append(" on ").append(info.getLockName());
    }
    if (info.getLockOwnerName() != null) {
      sb.append(" owned by ")
          .append(info.getLockOwnerName())
          .append('(')
          .append(info.getLockOwnerId())
          .append(')');
    }
    sb.append('\n');
    for (StackTraceElement ste : info.getStackTrace()) {
      sb.append("    at ").append(ste).append('\n');
    }
    sb.append('\n');
  }
}
