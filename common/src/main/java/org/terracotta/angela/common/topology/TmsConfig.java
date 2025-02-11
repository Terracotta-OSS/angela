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
package org.terracotta.angela.common.topology;

public class TmsConfig {

  private final String hostname;
  private final int tmsPort;

  public TmsConfig(String hostname, int tmsPort) {
    this.hostname = hostname;
    this.tmsPort = tmsPort;
  }

  /**
   * @deprecated Use {@link #getHostName()} instead
   */
  @Deprecated
  public String getHostname() {
    return getHostName();
  }

  public String getHostName() {
    return hostname;
  }

  public int getTmsPort() {
    return tmsPort;
  }

  public static TmsConfig noTms() {
    return null;
  }

  public static TmsConfig withTms(String hostname, int tmsPort) {
    return new TmsConfig(hostname, tmsPort);
  }

  public static TmsConfig hostnameAndPort(String hostname, int tmsPort) {
    return new TmsConfig(hostname, tmsPort);
  }
}
