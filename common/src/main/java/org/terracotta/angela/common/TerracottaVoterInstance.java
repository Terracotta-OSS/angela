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
import org.terracotta.angela.common.tcconfig.SecurityRootDirectory;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

public class TerracottaVoterInstance {
  private final TerracottaVoter terracottaVoter;
  private final File kitDir;
  private final DistributionController distributionController;
  private final SecurityRootDirectory securityRootDirectory;
  private final File workingDir;
  private final TerracottaCommandLineEnvironment tcEnv;
  private volatile TerracottaVoterInstance.TerracottaVoterInstanceProcess terracottaVoterInstanceProcess = new TerracottaVoterInstance.TerracottaVoterInstanceProcess(new AtomicReference<>(TerracottaVoterState.STOPPED));

  public TerracottaVoterInstance(TerracottaVoter terracottaVoter, DistributionController distributionController,
                                 File kitDir, File workingDir, SecurityRootDirectory securityRootDirectory,
                                 TerracottaCommandLineEnvironment tcEnv) {
    this.terracottaVoter = terracottaVoter;
    this.distributionController = distributionController;
    this.kitDir = kitDir;
    this.workingDir = workingDir;
    this.securityRootDirectory = securityRootDirectory;
    this.tcEnv = tcEnv;
  }

  public void start(Map<String, String> envOverrides) {
    terracottaVoterInstanceProcess = distributionController.startVoter(terracottaVoter, kitDir, workingDir, securityRootDirectory, tcEnv, envOverrides);
  }

  public void stop() {
    distributionController.stopVoter(terracottaVoterInstanceProcess);
  }

  public TerracottaVoterState getTerracottaVoterState() {
    return this.terracottaVoterInstanceProcess.getState();
  }

  public static class TerracottaVoterInstanceProcess {
    private final Set<Number> pids;
    private final AtomicReference<TerracottaVoterState> state;

    public TerracottaVoterInstanceProcess(AtomicReference<TerracottaVoterState> state, Number... pids) {
      for (Number pid : pids) {
        if (pid.intValue() < 1) {
          throw new IllegalArgumentException("Pid cannot be < 1");
        }
      }
      this.pids = new HashSet<>(Arrays.asList(pids));
      this.state = state;
    }

    public TerracottaVoterState getState() {
      return state.get();
    }

    public Set<Number> getPids() {
      return Collections.unmodifiableSet(pids);
    }
  }
}
