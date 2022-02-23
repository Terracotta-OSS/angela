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

import org.zeroturnaround.exec.ProcessExecutor;
import org.zeroturnaround.exec.StartedProcess;
import org.zeroturnaround.process.PidUtil;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;


class WatchedProcess<S extends Enum<S>> {

  private final StartedProcess startedProcess;
  private final int pid;

  public WatchedProcess(ProcessExecutor processExecutor, Consumer<S> stateRef, S deadState) {
    try {
      this.startedProcess = processExecutor.start();
    } catch (IOException e) {
      throw new RuntimeException("Cannot start process " + processExecutor.getCommand(), e);
    }
    this.pid = PidUtil.getPid(startedProcess.getProcess());

    Thread watcherThread = new Thread(() -> {
      try {
        startedProcess.getFuture().get();
        stateRef.accept(deadState);
      } catch (InterruptedException | ExecutionException e) {
        throw new RuntimeException(e);
      }
    });
    watcherThread.setDaemon(true);
    watcherThread.setName("ProcessWatcher on PID#" + pid);
    watcherThread.start();
  }

  public boolean isAlive() {
    return startedProcess.getProcess().isAlive();
  }

  public int getPid() {
    return pid;
  }
}
