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
package org.terracotta.angela.common;

import org.terracotta.angela.common.distribution.DistributionController;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

public class TerracottaManagementServerInstance {

  private final DistributionController distributionController;
  private final File kitDir;
  private final File workingDir;
  private final TerracottaCommandLineEnvironment tcEnv;
  private volatile TerracottaManagementServerInstance.TerracottaManagementServerInstanceProcess terracottaManagementServerInstanceProcess = new TerracottaManagementServerInstance.TerracottaManagementServerInstanceProcess(new AtomicReference<>(TerracottaManagementServerState.STOPPED));

  public TerracottaManagementServerInstance(final DistributionController distributionController, final File kitDir,
                                            File workingDir, TerracottaCommandLineEnvironment tcEnv) {
    this.distributionController = distributionController;
    this.kitDir = kitDir;
    this.workingDir = workingDir;
    this.tcEnv = tcEnv;
  }

  public void start(Map<String, String> envOverrides) {
    this.terracottaManagementServerInstanceProcess = this.distributionController.startTms(kitDir, workingDir, tcEnv, envOverrides);
  }

  public void stop() {
    this.distributionController.stopTms(kitDir, terracottaManagementServerInstanceProcess, tcEnv);
  }

  public TerracottaManagementServerState getTerracottaManagementServerState() {
    return this.terracottaManagementServerInstanceProcess.getState();
  }


  public static class TerracottaManagementServerInstanceProcess {
    private final Set<Number> pids;
    private final AtomicReference<TerracottaManagementServerState> state;

    public TerracottaManagementServerInstanceProcess(AtomicReference<TerracottaManagementServerState> state, Number... pids) {
      for (Number pid : pids) {
        if (pid.intValue() < 1) {
          throw new IllegalArgumentException("Pid cannot be < 1");
        }
      }
      this.pids = new HashSet<>(Arrays.asList(pids));
      this.state = state;
    }

    public TerracottaManagementServerState getState() {
      return state.get();
    }

    public Set<Number> getPids() {
      return Collections.unmodifiableSet(pids);
    }
  }

}
