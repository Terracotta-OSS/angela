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
package org.terracotta.angela;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terracotta.angela.common.TerracottaCommandLineEnvironment;
import org.terracotta.angela.common.net.PortAllocator;
import org.terracotta.angela.common.tcconfig.License;
import org.terracotta.angela.common.topology.LicenseType;
import org.terracotta.angela.common.topology.PackageType;
import org.terracotta.angela.common.topology.Version;
import org.terracotta.angela.common.util.FileUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

import static org.terracotta.angela.common.topology.PackageType.KIT;

/**
 * The KitResolver implementation will resolve the appropriate coordinates of the Terracotta installation
 * <p>
 * We rely on the Java SPI in order to dynamically load the appropriate implementation according to the type (KIT or SAG based)
 * and the license (OSS or EE).
 *
 * @author Aurelien Broszniowski
 */

public abstract class KitResolver {

  private static final Logger logger = LoggerFactory.getLogger(KitResolver.class);

  public void init(PortAllocator portAllocator) {
  }

  /**
   * Resolves the installer path on the local machine.
   * This will allow Angela to call the installer and do a local install.
   *
   * @param version     {@link Version}
   * @param licenseType {@link LicenseType}
   * @param packageType {@link PackageType}
   * @return path of the installer on the local machine
   */
  public abstract Path resolveLocalInstallerPath(Version version, LicenseType licenseType, PackageType packageType);

  /**
   * Uses the local installer and create a Terracotta install on the local machine.
   *
   * @param version              {@link Version}
   * @param packageType          {@link PackageType}
   * @param license              {@link License}
   * @param localInstallerPath   path of the installer on the local machine
   * @param rootInstallationPath directory where installs are stored for caching
   * @param env optional env values
   */
  public abstract void createLocalInstallFromInstaller(Version version, PackageType packageType, License license, Path localInstallerPath, Path rootInstallationPath, TerracottaCommandLineEnvironment env);

  /**
   * Resolves the root of the local Terracotta install path.
   *
   * @param version              {@link Version}
   * @param packageType          {@link PackageType}
   * @param localInstallerPath   path of the installer on the local machine
   * @param rootInstallationPath directory where installs are stored for caching
   * @return path
   */
  public abstract Path resolveKitInstallationPath(Version version, PackageType packageType, Path localInstallerPath, Path rootInstallationPath);

  /**
   * Resolves the Terracotta installation kit URL.
   *
   * @param version     {@link Version}
   * @param licenseType {@link LicenseType}
   * @param packageType {@link PackageType}
   * @return URL of the installation kit
   */
  public abstract URL[] resolveKitUrls(Version version, LicenseType licenseType, PackageType packageType);

  /**
   * Verifies if the {@link LicenseType} is supported by the KitResolver implementation.
   *
   * @param licenseType {@link LicenseType}
   * @return true if the LicenseType is supported
   */
  public abstract boolean supports(LicenseType licenseType);

  /**
   * Downloads the installer on the local machine
   *
   * @param version            {@link Version}
   * @param licenseType        {@link LicenseType}
   * @param packageType        {@link PackageType}
   * @param localInstallerFile path of the installer on the local machine
   */
  public void downloadLocalInstaller(Version version, LicenseType licenseType, PackageType packageType, Path localInstallerFile) {
    URL[] urls = resolveKitUrls(version, licenseType, packageType);
    URL kitUrl = urls[0];
    URL md5Url = urls[1];
    download(kitUrl, localInstallerFile);

    // snapshots and SAG installer have no MD5
    if (!version.isSnapshot() && packageType == KIT) {
      download(md5Url, Paths.get(localInstallerFile + ".md5"));
    }
    logger.debug("Success -> file downloaded successfully");
  }

  protected void download(URL url, Path dest) {
    logger.info("Downloading: {} to: {}", url, dest);

    try {
      URLConnection urlConnection = url.openConnection();
      urlConnection.connect();

      int contentLength = urlConnection.getContentLength();

      createParentDirs(dest);

      long lastProgress = -1;
      try (OutputStream fos = Files.newOutputStream(dest);
           InputStream is = url.openStream()) {
        byte[] buffer = new byte[8192];
        long len = 0;
        int count;
        while ((count = is.read(buffer)) != -1) {
          len += count;

          long progress = 100 * len / contentLength;
          if (progress % 10 == 0 && progress > lastProgress) {
            logger.debug("Download progress = {}%", progress);
            lastProgress = progress;
          }
          fos.write(buffer, 0, count);
        }
      }

      logger.debug("Success -> file downloaded successfully");
    } catch (IOException e) {
      // messed up download -> delete it
      logger.debug("Deleting: " + dest.getParent() + " dir as it's messed up");
      FileUtils.deleteQuietly(dest.getParent());
      throw new UncheckedIOException(e);
    }
  }

  private static void createParentDirs(Path file) throws IOException {
    Objects.requireNonNull(file);
    Path parent = file.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
  }
}
