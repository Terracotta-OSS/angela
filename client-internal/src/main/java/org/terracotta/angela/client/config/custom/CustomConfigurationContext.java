/*
 * The contents of this file are subject to the Terracotta Public License Version
 * 2.0 (the "License"); You may not use this file except in compliance with the
 * License. You may obtain a copy of the License at
 *
 * http://terracotta.org/legal/terracotta-public-license.
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License for
 * the specific language governing rights and limitations under the License.
 *
 * The Covered Software is Angela.
 *
 * The Initial Developer of the Covered Software is
 * Terracotta, Inc., a Software AG company
 */

package org.terracotta.angela.client.config.custom;

import org.terracotta.angela.client.remote.agent.SshRemoteAgentLauncher;
import org.terracotta.angela.client.config.ClientArrayConfigurationContext;
import org.terracotta.angela.client.config.ConfigurationContext;
import org.terracotta.angela.client.config.MonitoringConfigurationContext;
import org.terracotta.angela.client.config.RemotingConfigurationContext;
import org.terracotta.angela.client.config.TmsConfigurationContext;
import org.terracotta.angela.client.config.TsaConfigurationContext;
import org.terracotta.angela.common.TerracottaCommandLineEnvironment;
import org.terracotta.angela.common.distribution.Distribution;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

public class CustomConfigurationContext implements ConfigurationContext {

  /**
   * @deprecated Use {@link TerracottaCommandLineEnvironment#DEFAULT} instead.
   */
  @Deprecated
  public static final Set<String> DEFAULT_ALLOWED_JDK_VENDORS = TerracottaCommandLineEnvironment.DEFAULT_ALLOWED_JDK_VENDORS;

  /**
   * @deprecated Use {@link TerracottaCommandLineEnvironment#DEFAULT} instead.
   */
  @Deprecated
  public static final String DEFAULT_JDK_VERSION = TerracottaCommandLineEnvironment.DEFAULT_JDK_VERSION;

  private CustomRemotingConfigurationContext customRemotingConfigurationContext = new CustomRemotingConfigurationContext().remoteAgentLauncherSupplier(SshRemoteAgentLauncher::new);
  private CustomTsaConfigurationContext customTsaConfigurationContext;
  private CustomTmsConfigurationContext customTmsConfigurationContext;
  private CustomMonitoringConfigurationContext customMonitoringConfigurationContext;
  private CustomClientArrayConfigurationContext customClientArrayConfigurationContext;

  public static CustomConfigurationContext customConfigurationContext() {
    return new CustomConfigurationContext();
  }

  protected CustomConfigurationContext() {
  }

  @Override
  public RemotingConfigurationContext remoting() {
    return customRemotingConfigurationContext;
  }

  public CustomConfigurationContext remoting(CustomRemotingConfigurationContext customRemotingConfigurationContext) {
    this.customRemotingConfigurationContext = customRemotingConfigurationContext;
    return this;
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
    if (!customTsaConfigurationContext.getTopology().getLicenseType().isOpenSource() && customTsaConfigurationContext.getLicense() == null) {
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
  public ClientArrayConfigurationContext clientArray() {
    return customClientArrayConfigurationContext;
  }

  public CustomConfigurationContext clientArray(Consumer<CustomClientArrayConfigurationContext> clientArray) {
    if (customClientArrayConfigurationContext != null) {
      throw new IllegalStateException("client array config already defined");
    }
    customClientArrayConfigurationContext = new CustomClientArrayConfigurationContext();
    clientArray.accept(customClientArrayConfigurationContext);
    Distribution distribution = customClientArrayConfigurationContext.getClientArrayTopology().getDistribution();
    if (distribution != null && !distribution.getLicenseType().isOpenSource() && customClientArrayConfigurationContext.getLicense() == null) {
      throw new IllegalArgumentException("Distribution's license type '" + distribution.getLicenseType() + "' requires a license.");
    }
    return this;
  }

  @Override
  public Set<String> allHostnames() {
    Set<String> hostnames = new HashSet<>();
    if (customTsaConfigurationContext != null) {
      hostnames.addAll(customTsaConfigurationContext.getTopology().getServersHostnames());
    }
    if (customTmsConfigurationContext != null) {
      hostnames.add(customTmsConfigurationContext.getHostname());
    }
    if (customClientArrayConfigurationContext != null) {
      hostnames.addAll(customClientArrayConfigurationContext.getClientArrayTopology().getClientHostnames());
    }
    return hostnames;
  }

  @Override
  public MonitoringConfigurationContext monitoring() {
    return customMonitoringConfigurationContext;
  }

  public CustomConfigurationContext monitoring(Consumer<CustomMonitoringConfigurationContext> consumer) {
    if (customMonitoringConfigurationContext != null) {
      throw new IllegalStateException("Monitoring config already defined");
    }
    customMonitoringConfigurationContext = new CustomMonitoringConfigurationContext();
    consumer.accept(customMonitoringConfigurationContext);
    return this;
  }
}
