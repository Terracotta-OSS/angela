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
package org.terracotta.angela.client;

import org.terracotta.angela.agent.AgentController;
import org.terracotta.angela.agent.com.AgentID;
import org.terracotta.angela.agent.com.Executor;
import org.terracotta.angela.common.TerracottaCommandLineEnvironment;
import org.terracotta.angela.common.ToolExecutionResult;
import org.terracotta.angela.common.tcconfig.TerracottaServer;
import org.terracotta.angela.common.topology.InstanceId;

public class Jcmd {

  private final TerracottaCommandLineEnvironment tcEnv;
  private final Executor executor;
  private final InstanceId instanceId;
  private final TerracottaServer terracottaServer;
  private final Client client;

  Jcmd(Executor executor, InstanceId instanceId, TerracottaServer terracottaServer, TerracottaCommandLineEnvironment tcEnv) {
    this.executor = executor;
    this.instanceId = instanceId;
    this.terracottaServer = terracottaServer;
    this.client = null;
    this.tcEnv = tcEnv;
  }

  Jcmd(Executor executor, Client client, TerracottaCommandLineEnvironment tcEnv) {
    this.executor = executor;
    this.instanceId = null;
    this.terracottaServer = null;
    this.client = client;
    this.tcEnv = tcEnv;
  }

  /**
   * Execute jcmd on the target JVM. This basically creates and execute a command line looking like the following:
   * ${JAVA_HOME}/bin/jcmd &lt;the JVM's PID&gt; &lt;arguments&gt;
   *
   * @param arguments The arguments to pass to jcmd after the PID.
   * @return A representation of the jcmd execution
   */
  public ToolExecutionResult executeCommand(String... arguments) {
    if (terracottaServer != null) {
      final AgentID agentID = executor.getAgentID(terracottaServer.getHostName());
      return executor.execute(agentID, () -> AgentController.getInstance().serverJcmd(instanceId, terracottaServer, tcEnv, arguments));

    } else if (client != null) {
      final AgentID agentID = executor.getAgentID(client.getHostName());
      final long pid = client.getPid();
      return executor.execute(agentID, () -> AgentController.getInstance().clientJcmd(pid, tcEnv, arguments));

    } else {
      throw new AssertionError();
    }
  }

}
