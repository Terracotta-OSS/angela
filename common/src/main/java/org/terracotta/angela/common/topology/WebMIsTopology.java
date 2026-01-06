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

import org.terracotta.angela.common.distribution.Distribution;
import org.terracotta.angela.common.isconfig.WebMIsServer;
import org.terracotta.angela.common.tcconfig.License;

/**
 * @author Aurelien Broszniowski
 */

public class WebMIsTopology {
  private final Distribution distribution;
  private final WebMIsServer webMIsServer;
  private final License webMIsLicense;
  private final License terracottaLicence;

  public WebMIsTopology(Distribution distribution, WebMIsServer webMIsServer, License webMIsLicense) {
    this(distribution, webMIsServer, webMIsLicense, null);
  }

  public WebMIsTopology(Distribution distribution, WebMIsServer webMIsServer, License webMIsLicense, License terracottaLicence) {
    this.distribution = distribution;
    this.webMIsServer = webMIsServer;
    this.webMIsLicense = webMIsLicense;
    this.terracottaLicence = terracottaLicence;
  }

  public Distribution getDistribution() {
    return distribution;
  }

  public WebMIsServer getWebMIsServer() {
    return webMIsServer;
  }

  public License getWebMIsLicense() {
    return webMIsLicense;
  }

  public License getTerracottaLicence() {
    return terracottaLicence;
  }
}
