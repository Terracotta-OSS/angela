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
package org.terracotta.angela.client.support.junit;

import org.junit.runner.Description;
import org.terracotta.angela.agent.com.Executor;
import org.terracotta.angela.agent.com.grid.ignite.IgniteSshRemoteExecutor;
import org.terracotta.angela.client.AngelaOrchestrator;
import org.terracotta.angela.client.ClusterFactory;
import org.terracotta.angela.client.config.ConfigurationContext;
import org.terracotta.angela.common.net.PortAllocator;

import java.util.function.Consumer;

/**
 * @author Mathieu Carbou
 */
public class AngelaOrchestratorRule extends ExtendedTestRule {

  private AngelaOrchestrator.AngelaOrchestratorBuilder builder = AngelaOrchestrator.builder();
  private AngelaOrchestrator angelaOrchestrator;

  /**
   * @deprecated Use {@link #igniteFree()} instead
   */
  @Deprecated
  public AngelaOrchestratorRule local() {
    return igniteFree();
  }

  public AngelaOrchestratorRule withPortAllocator(PortAllocator portAllocator) {
    builder = builder.withPortAllocator(portAllocator);
    return this;
  }

  public AngelaOrchestratorRule igniteRemote() {
    builder = builder.igniteRemote();
    return this;
  }

  public AngelaOrchestratorRule igniteRemote(Consumer<IgniteSshRemoteExecutor> configurator) {
    builder = builder.igniteRemote(configurator);
    return this;
  }

  public AngelaOrchestratorRule igniteLocal() {
    builder = builder.igniteLocal();
    return this;
  }

  public AngelaOrchestratorRule igniteFree() {
    builder = builder.igniteFree();
    return this;
  }

  public AngelaOrchestrator getAngelaOrchestrator() {
    if (angelaOrchestrator == null) {
      throw new IllegalStateException("Not initialized");
    }
    return angelaOrchestrator;
  }

  @Override
  protected void before(Description description) {
    angelaOrchestrator = builder.build();
  }

  public Executor getExecutor() {return angelaOrchestrator.getExecutor();}

  public PortAllocator getPortAllocator() {return getAngelaOrchestrator().getPortAllocator();}

  public ClusterFactory newClusterFactory(String idPrefix, ConfigurationContext configurationContext) {
    return getAngelaOrchestrator().newClusterFactory(idPrefix, configurationContext);
  }

  @Override
  protected void after(Description description) {
    angelaOrchestrator.close();
  }
}
