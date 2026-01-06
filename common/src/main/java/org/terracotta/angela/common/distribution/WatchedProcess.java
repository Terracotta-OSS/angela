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
package org.terracotta.angela.common.distribution;

import org.zeroturnaround.exec.ProcessExecutor;
import org.zeroturnaround.exec.ProcessResult;
import org.zeroturnaround.exec.StartedProcess;
import org.zeroturnaround.exec.listener.ProcessListener;

import static org.terracotta.angela.common.util.Pids.of;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;


class WatchedProcess<S extends Enum<S>> {

  private final StartedProcess startedProcess;
  private final long pid;

  public WatchedProcess(ProcessExecutor processExecutor, final AtomicReference<S> stateRef, final S deadState) {
    processExecutor.addListener(new ProcessListener() {
      @Override
      public void afterFinish(Process process, ProcessResult result) {
        stateRef.set(deadState);
      }

      @Override
      public void afterStop(Process process) {
        stateRef.set(deadState);
      }
    });

    try {
      this.startedProcess = processExecutor.start();
    } catch (IOException e) {
      throw new RuntimeException("Cannot start process " + processExecutor.getCommand(), e);
    }
    this.pid = of(startedProcess.getProcess());
  }

  public boolean isAlive() {
    return startedProcess.getProcess().isAlive();
  }

  public long getPid() {
    return pid;
  }
}
