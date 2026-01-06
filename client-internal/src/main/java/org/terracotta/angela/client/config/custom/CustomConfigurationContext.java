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

import org.terracotta.angela.client.config.ClientArrayConfigurationContext;
import org.terracotta.angela.client.config.ConfigurationContext;
import org.terracotta.angela.client.config.ConfigurationContextVisitor;
import org.terracotta.angela.client.config.MonitoringConfigurationContext;
import org.terracotta.angela.client.config.TmsConfigurationContext;
import org.terracotta.angela.client.config.ToolConfigurationContext;
import org.terracotta.angela.client.config.TsaConfigurationContext;
import org.terracotta.angela.client.config.VoterConfigurationContext;
import org.terracotta.angela.client.config.WebMIsConfigurationContext;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

public class CustomConfigurationContext implements ConfigurationContext {
  private CustomTsaConfigurationContext customTsaConfigurationContext;
  private CustomTmsConfigurationContext customTmsConfigurationContext;
  private CustomMonitoringConfigurationContext customMonitoringConfigurationContext;
  private final List<CustomClientArrayConfigurationContext> customClientArrayConfigurationContexts = new ArrayList<>();
  private CustomVoterConfigurationContext customVoterConfigurationContext;
  private CustomClusterToolConfigurationContext customClusterToolConfigurationContext;
  private CustomImportToolConfigurationContext customImportToolConfigurationContext;
  private CustomConfigToolConfigurationContext customConfigToolConfigurationContext;
  private CustomWebMIsConfigurationContext customWebMIsConfigurationContext;

  public static CustomConfigurationContext customConfigurationContext() {
    return new CustomConfigurationContext();
  }

  protected CustomConfigurationContext() {
  }

  @Override
  public void visit(ConfigurationContextVisitor visitor) {
    if (customTsaConfigurationContext != null) {
      visitor.visit(customTsaConfigurationContext);
    }
    if (customVoterConfigurationContext != null) {
      visitor.visit(customVoterConfigurationContext);
    }
  }

  @Override
  public TsaConfigurationContext tsa() {
    return customTsaConfigurationContext;
  }

  public CustomConfigurationContext tsa(Consumer<CustomTsaConfigurationContext> tsa) {
    if (customTsaConfigurationContext != null) {
      throw new IllegalStateException("TSA config already defined");
    }
    customTsaConfigurationContext = new CustomTsaConfigurationContext();
    tsa.accept(customTsaConfigurationContext);
    if (customTsaConfigurationContext.getTopology() == null) {
      throw new IllegalArgumentException("You added a tsa to the Configuration but did not define its topology");
    }
    if (customTsaConfigurationContext.getLicense() == null && !customTsaConfigurationContext.getTopology().getLicenseType().isOpenSource()) {
      throw new IllegalArgumentException("LicenseType " + customTsaConfigurationContext.getTopology().getLicenseType() + " requires a license.");
    }
    return this;
  }

  @Override
  public TmsConfigurationContext tms() {
    return customTmsConfigurationContext;
  }

  public CustomConfigurationContext tms(Consumer<CustomTmsConfigurationContext> tms) {
    if (customTmsConfigurationContext != null) {
      throw new IllegalStateException("TMS config already defined");
    }
    customTmsConfigurationContext = new CustomTmsConfigurationContext();
    tms.accept(customTmsConfigurationContext);
    if (customTmsConfigurationContext.getLicense() == null) {
      throw new IllegalArgumentException("TMS requires a license.");
    }
    return this;
  }

  @Override
  public List<? extends ClientArrayConfigurationContext> clientArray() {
    return customClientArrayConfigurationContexts;
  }

  public CustomConfigurationContext clientArray(Consumer<CustomClientArrayConfigurationContext> clientArray) {
    CustomClientArrayConfigurationContext customClientArrayConfigurationContext = new CustomClientArrayConfigurationContext();
    clientArray.accept(customClientArrayConfigurationContext);
    customClientArrayConfigurationContexts.add(customClientArrayConfigurationContext);
    return this;
  }

  @Override
  public Set<String> allHostnames() {
    Set<String> hostnames = new HashSet<>();
    if (customTsaConfigurationContext != null) {
      hostnames.addAll(customTsaConfigurationContext.getTopology().getServersHostnames());
    }
    if (customTmsConfigurationContext != null) {
      hostnames.add(customTmsConfigurationContext.getHostName());
    }
    for (CustomClientArrayConfigurationContext customClientArrayConfigurationContext : customClientArrayConfigurationContexts) {
      hostnames.addAll(customClientArrayConfigurationContext.getClientArrayTopology().getClientHostnames());
    }
    return hostnames;
  }

  @Override
  public MonitoringConfigurationContext monitoring() {
    return customMonitoringConfigurationContext;
  }

  @Override
  public ToolConfigurationContext clusterTool() {
    return customClusterToolConfigurationContext;
  }

  @Override
  public ToolConfigurationContext configTool() {
    return customConfigToolConfigurationContext;
  }
  
  @Override
  public ToolConfigurationContext importTool() {
    return customImportToolConfigurationContext;
  }

  @Override
  public VoterConfigurationContext voter() {
    return customVoterConfigurationContext;
  }

  public CustomConfigurationContext clusterTool(Consumer<CustomClusterToolConfigurationContext> clusterTool) {
    if (customClusterToolConfigurationContext != null) {
      throw new IllegalStateException("Cluster tool config already defined");
    }
    customClusterToolConfigurationContext = new CustomClusterToolConfigurationContext();
    clusterTool.accept(customClusterToolConfigurationContext);
    return this;
  }

  public CustomConfigurationContext configTool(Consumer<CustomConfigToolConfigurationContext> configTool) {
    if (customConfigToolConfigurationContext != null) {
      throw new IllegalStateException("Config tool config already defined");
    }
    customConfigToolConfigurationContext = new CustomConfigToolConfigurationContext();
    configTool.accept(customConfigToolConfigurationContext);
    return this;
  }
  
  public CustomConfigurationContext importTool(Consumer<CustomImportToolConfigurationContext> importTool) {
    if (customImportToolConfigurationContext != null) {
      throw new IllegalStateException("Import tool config already defined");
    }
    customImportToolConfigurationContext = new CustomImportToolConfigurationContext();
    importTool.accept(customImportToolConfigurationContext);
    return this;
  }

  public CustomConfigurationContext voter(Consumer<CustomVoterConfigurationContext> voter) {
    if (customVoterConfigurationContext != null) {
      throw new IllegalStateException("Voter config already defined");
    }
    customVoterConfigurationContext = new CustomVoterConfigurationContext();
    voter.accept(customVoterConfigurationContext);
    return this;
  }

  public CustomConfigurationContext monitoring(Consumer<CustomMonitoringConfigurationContext> consumer) {
    if (customMonitoringConfigurationContext != null) {
      throw new IllegalStateException("Monitoring config already defined");
    }
    customMonitoringConfigurationContext = new CustomMonitoringConfigurationContext();
    consumer.accept(customMonitoringConfigurationContext);
    return this;
  }

  public WebMIsConfigurationContext webMIs() {
    return this.customWebMIsConfigurationContext;
  }

  public CustomConfigurationContext webMIs(Consumer<CustomWebMIsConfigurationContext> is) {
    if (this.customWebMIsConfigurationContext != null) {
      throw new IllegalStateException("TSA config already defined");
    } else {
      this.customWebMIsConfigurationContext = new CustomWebMIsConfigurationContext();
      is.accept(this.customWebMIsConfigurationContext);
      if (this.customWebMIsConfigurationContext.getTopology() == null) {
        throw new IllegalArgumentException("You added an Is to the Configuration but did not define its topology");
      } else {
        return this;
      }
    }
  }

}
