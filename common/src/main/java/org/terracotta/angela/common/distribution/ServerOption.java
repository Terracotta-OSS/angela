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

/**
 * Enum mapping server startup options between deprecated (old) and new formats.
 *
 * <p>Old format uses short options (e.g., -s, -p, -n) or double-dash long options (e.g., --hostname).
 * New format uses single-dash long options (e.g., -hostname, -port, -name).
 *
 * <p>Both formats are supported by the Terracotta server through:
 * <ul>
 *   <li>{@code DeprecatedOptionsParsingImpl} - handles old format</li>
 *   <li>{@code OptionsParsingImpl} - handles new format</li>
 * </ul>
 */
public enum ServerOption {
  CLUSTER_NAME("-N", "-cluster-name"),
  NODE_NAME("-n", "-name"),
  NODE_HOSTNAME("-s", "-hostname"),
  NODE_PORT("-p", "-port"),
  NODE_GROUP_PORT("-g", "-group-port"),
  NODE_BIND_ADDRESS("-a", "-bind-address"),
  NODE_GROUP_BIND_ADDRESS("-A", "-group-bind-address"),
  NODE_CONFIG_DIR("-r", "-config-dir"),
  NODE_HOME_DIR("--server-home", "-server-home"),
  NODE_METADATA_DIR("-m", "-metadata-dir"),
  NODE_LOG_DIR("-L", "-log-dir"),
  NODE_BACKUP_DIR("-b", "-backup-dir"),
  SECURITY_DIR("-x", "-security-dir"),
  SECURITY_AUDIT_LOG_DIR("-u", "-audit-log-dir"),
  SECURITY_AUTHC("-z", "-authc"),
  SECURITY_SSL_TLS("-t", "-ssl-tls"),
  SECURITY_WHITELIST("-w", "-whitelist"),
  FAILOVER_PRIORITY("-y", "-failover-priority"),
  CLIENT_RECONNECT_WINDOW("-R", "-client-reconnect-window"),
  CLIENT_LEASE_DURATION("-i", "-client-lease-duration"),
  OFFHEAP_RESOURCES("-o", "-offheap-resources"),
  TC_PROPERTIES("-T", "-tc-properties"),
  DATA_DIRS("-d", "-data-dirs"),
  CONFIG_FILE("-f", "-config-file"),
  RELAY(null, "-relay"),
  REPLICA_HOSTNAME(null, "-replica-hostname"),
  REPLICA_PORT(null, "-replica-port"),
  REPLICA(null, "-replica"),
  RELAY_HOSTNAME(null, "-relay-hostname"),
  RELAY_PORT(null, "-relay-port"),
  RELAY_GROUP_PORT(null, "-relay-group-port");

  private final String deprecatedOption;
  private final String option;

  ServerOption(String deprecatedOption, String option) {
    this.deprecatedOption = deprecatedOption;
    this.option = option;
  }

  /**
   * Returns the option in new format
   *
   * @return new format option (e.g., "-hostname", "-port", "-name")
   */
  public String getOption() {
    return option;
  }

  /**
   * Returns the deprecated (old) format option
   *
   * @return deprecated format option, or null if not available in old format
   */
  public String getDeprecatedOption() {
    return deprecatedOption;
  }
}
