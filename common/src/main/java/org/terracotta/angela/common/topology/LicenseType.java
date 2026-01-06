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

import org.terracotta.angela.common.tcconfig.License;

/**
 * @author Aurelien Broszniowski
 */
public enum LicenseType {
  // 4.x:
  GO("bigmemory-go", "/licenses/terracotta-license.key"),
  MAX("bigmemory-max", "/licenses/terracotta-license.key"),

  // 10.x:
  TERRACOTTA_OS(null, null),
  TERRACOTTA("terracotta-db", "/licenses/Terracotta101.xml"),


  WEBM_IS(null, "/licenses/msrKey.xml"),
  WEBM_MSR(null, "/licenses/msrKey.xml");

  private final String kratosTag;
  private final String defaultLicenseResourceName;

  LicenseType(String kratosTag, String defaultLicenseResourceName) {
    this.kratosTag = kratosTag;
    this.defaultLicenseResourceName = defaultLicenseResourceName;
  }

  public boolean isOpenSource() {
    return kratosTag == null;
  }

  public String getKratosTag() {
    return kratosTag;
  }

  public License defaultLicense() {
    return defaultLicenseResourceName == null ? null : new License(LicenseType.class.getResource(defaultLicenseResourceName));
  }
}
