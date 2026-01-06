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

import org.terracotta.angela.common.AngelaProperties;
import org.terracotta.angela.common.topology.LicenseType;
import org.terracotta.angela.common.topology.PackageType;
import org.terracotta.angela.common.topology.Version;

import java.io.Serializable;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Objects;

import static java.util.Objects.requireNonNull;

/**
 * @author Aurelien Broszniowski
 */

public class Distribution implements Serializable {
  private static final long serialVersionUID = 1L;

  private final Version version;
  private final PackageType packageType;
  private final LicenseType licenseType;
  private final EnumSet<RuntimeOption> runtimeOptions;
  private Class<? extends DistributionController> distributionControllerType;

  private Distribution(Version version, PackageType packageType, LicenseType licenseType, EnumSet<RuntimeOption> runtimeOptions) {
    this.version = requireNonNull(version);
    this.packageType = requireNonNull(packageType);
    this.licenseType = licenseType;
    this.runtimeOptions = runtimeOptions;
  }

  public static Distribution distribution(Version version, PackageType packageType, LicenseType licenseType) {
    return new Distribution(version, packageType, licenseType, EnumSet.noneOf(RuntimeOption.class));
  }

  public static Distribution distribution(PackageType packageType, LicenseType licenseType) {
    return new Distribution(Version.version(AngelaProperties.DISTRIBUTION.getValue()), packageType, licenseType, EnumSet.noneOf(RuntimeOption.class));
  }

  public static Distribution distribution(Version version, PackageType packageType, LicenseType licenseType, RuntimeOption... runtime) {
    return new Distribution(version, packageType, licenseType, runtime.length == 0 ? EnumSet.noneOf(RuntimeOption.class) : EnumSet.copyOf(Arrays.asList(runtime)));
  }

  public static Distribution distribution(PackageType packageType, LicenseType licenseType, RuntimeOption... runtime) {
    return new Distribution(Version.version(AngelaProperties.DISTRIBUTION.getValue()), packageType, licenseType, runtime.length == 0 ? EnumSet.noneOf(RuntimeOption.class) : EnumSet.copyOf(Arrays.asList(runtime)));
  }

  /**
   * Manually select the DistributionController implementation to use
   */
  public Distribution withDistributionController(Class<? extends DistributionController> distributionControllerType) {
    this.distributionControllerType = requireNonNull(distributionControllerType);
    return this;
  }

  public Version getVersion() {
    return version;
  }

  public PackageType getPackageType() {
    return packageType;
  }

  public LicenseType getLicenseType() {
    return licenseType;
  }

  public Class<? extends DistributionController> getDistributionControllerType() {
    // manually set ?
    if (distributionControllerType != null) {
      return distributionControllerType;
    }

    // 12.x => TCDB with dynamic config, import-export tool + webMethods IS support
    if (version.getMajor() == 12) {
      return runtimeOptions.contains(RuntimeOption.INLINE_SERVERS) ? Distribution107InlineController.class : Distribution120Controller.class;
    }

    // 11.x => TCDB with dynamic config + webMethods IS support
    if (version.getMajor() == 11) {
      return runtimeOptions.contains(RuntimeOption.INLINE_SERVERS) ? Distribution107InlineController.class : Distribution107Controller.class;
    }

    // 10.7 and above => TCDB with dynamic config + webMethods IS support
    if (version.getMajor() == 10 && version.getMinor() >= 7) {
      return runtimeOptions.contains(RuntimeOption.INLINE_SERVERS) ? Distribution107InlineController.class : Distribution107Controller.class;
    }

    // 10.2 to 10.6 => TCDB before dynamic config
    if (version.getMajor() == 10 && version.getMinor() < 7) {
      return Distribution102Controller.class;
    }

    // 5.7, 5.8, 5.9, 5.10, 5.11 and above => platform layout and test kit
    if (version.getMajor() == 5 && version.getMinor() >= 7) {
      return runtimeOptions.contains(RuntimeOption.INLINE_SERVERS) ? Distribution107InlineController.class : Distribution107Controller.class;
    }

    // 4.3, 4.4 and above => BM
    if (version.getMajor() == 4 && version.getMinor() >= 3) {
      return Distribution43Controller.class;
    }

    // 3.9 and above => Ehcache
    if (version.getMajor() == 3 && version.getMinor() >= 9) {
      return runtimeOptions.contains(RuntimeOption.INLINE_SERVERS) ? Distribution107InlineController.class : Distribution107Controller.class;
    }

    // 3.8 at most => Ehcache before dynamic config
    if (version.getMajor() == 3 || version.getMinor() < 9) {
      return Distribution102Controller.class;
    }

    // failed to determine controller to use
    throw new IllegalStateException("Cannot create a DistributionController for version: " + version);
  }

  public DistributionController createDistributionController() {
    try {
      return getDistributionControllerType().getConstructor(getClass()).newInstance(this);
    } catch (InstantiationException | IllegalAccessException | IllegalArgumentException | InvocationTargetException | NoSuchMethodException | SecurityException e) {
      throw new IllegalStateException("Cannot create a DistributionController for version: " + version, e);
    }
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    Distribution that = (Distribution) o;
    return Objects.equals(version, that.version) &&
        packageType == that.packageType &&
        licenseType == that.licenseType;
  }

  @Override
  public int hashCode() {
    return Objects.hash(version, packageType, licenseType);
  }

  @Override
  public String toString() {
    return "Distribution{" +
        "version=" + version +
        ", packageType=" + packageType +
        ", licenseType=" + licenseType +
        '}';
  }
}
