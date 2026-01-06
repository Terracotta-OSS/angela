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
package org.terracotta.angela.common;

import org.terracotta.angela.common.distribution.DistributionController;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author Aurelien Broszniowski
 */

public class WebMIsServerInstance {

  private final DistributionController distributionController;
  private final File kitDir;
  private final File workingDir;
  private final TerracottaCommandLineEnvironment tcEnv;
  private volatile WebMIsServerInstance.WebMIsServerInstanceProcess webMIsServerInstanceProcess = new WebMIsServerInstance.WebMIsServerInstanceProcess(new AtomicReference<>(WebMIsServerState.STOPPED));

  public WebMIsServerInstance(DistributionController distributionController, File kitDir, File workingDir, TerracottaCommandLineEnvironment tcEnv) {
    this.distributionController = distributionController;
    this.kitDir = kitDir;
    this.workingDir = workingDir;
    this.tcEnv = tcEnv;
  }

  public WebMIsServerState getWebMIsServerState() {
    return this.webMIsServerInstanceProcess.getState();
  }

  public void start() {
    this.webMIsServerInstanceProcess = this.distributionController.startWebMIs(kitDir, workingDir, tcEnv);
  }

  public void stop() {
    this.distributionController.stopWebMIs(this.webMIsServerInstanceProcess, kitDir, workingDir, tcEnv);
  }

  public static class WebMIsServerInstanceProcess {
    private final Set<Number> pids;
    private final AtomicReference<WebMIsServerState> state;

    public WebMIsServerInstanceProcess(AtomicReference<WebMIsServerState> state, Number... pids) {
      for (Number pid : pids) {
        if (pid.intValue() < 1) {
          throw new IllegalArgumentException("Pid cannot be < 1");
        }
      }
      this.pids = new HashSet<>(Arrays.asList(pids));
      this.state = state;
    }

    public WebMIsServerState getState() {
      return state.get();
    }

    public void setState(WebMIsServerState state) {
      this.state.set(state);
    }

    public Set<Number> getPids() {
      return Collections.unmodifiableSet(pids);
    }
  }
}
