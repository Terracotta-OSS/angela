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
package org.terracotta.angela.client.config.custom;

import org.terracotta.angela.client.config.WebMIsConfigurationContext;
import org.terracotta.angela.common.TerracottaCommandLineEnvironment;
import org.terracotta.angela.common.distribution.Distribution;
import org.terracotta.angela.common.tcconfig.License;
import org.terracotta.angela.common.topology.WebMIsTopology;

public class CustomWebMIsConfigurationContext implements WebMIsConfigurationContext {

  private TerracottaCommandLineEnvironment terracottaCommandLineEnvironment = TerracottaCommandLineEnvironment.DEFAULT;
  private WebMIsTopology topology;

  public CustomWebMIsConfigurationContext topology(WebMIsTopology topology) {
    this.topology = topology;
    return this;
  }

  @Override
  public WebMIsTopology getTopology() {
    return this.topology;
  }

  public CustomWebMIsConfigurationContext terracottaCommandLineEnvironment(TerracottaCommandLineEnvironment terracottaCommandLineEnvironment) {
    this.terracottaCommandLineEnvironment = terracottaCommandLineEnvironment;
    return this;
  }

  @Override
  public TerracottaCommandLineEnvironment getTerracottaCommandLineEnvironment() {
    return terracottaCommandLineEnvironment;
  }

}
