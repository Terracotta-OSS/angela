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
package org.terracotta.angela.agent.kit;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terracotta.angela.KitResolver;
import org.terracotta.angela.common.TerracottaCommandLineEnvironment;
import org.terracotta.angela.common.distribution.Distribution;
import org.terracotta.angela.common.net.PortAllocator;
import org.terracotta.angela.common.tcconfig.License;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toList;

/**
 * @author Aurelien Broszniowski
 */
public class LocalKitManager extends KitManager {
  private static final Logger logger = LoggerFactory.getLogger(LocalKitManager.class);
  private final Map<String, File> clientJars = new HashMap<>();
  private final KitResolver kitResolver;
  static final String INSTALLATION_LOCK_FILE_NAME = "angela-install.lock";

  public LocalKitManager(PortAllocator portAllocator, Distribution distribution) {
    super(distribution);

    if (distribution != null) {
      final ServiceLoader<KitResolver> kitResolvers = ServiceLoader.load(KitResolver.class);
      KitResolver currentKitResolver = null;
      int kitResolverCount = 0;
      for (KitResolver kitResolver : kitResolvers) {
        kitResolverCount++;
        if (kitResolver.supports(distribution.getLicenseType())) {
          if (currentKitResolver != null) {
            throw new IllegalStateException("Found several service implementation for KitResolver for LicenseType " + distribution
                .getLicenseType());
          }
          currentKitResolver = kitResolver;
        }
      }
      if (currentKitResolver == null) {
        throw new IllegalArgumentException("Current LicenceType " + distribution.getLicenseType() + " can't find a corresponding KitResolver service (" + kitResolverCount + " services available)");
      } else {
        this.kitResolver = currentKitResolver;
        this.kitResolver.init(portAllocator);
      }
    } else {
      this.kitResolver = null;
    }
  }

  // TODO REFACTOR with below setupLocalInstall method according to parameter Distribution
  public void setupLocalWebMInstall(License license, TerracottaCommandLineEnvironment env) {
      Path localInstallerPath = rootInstallationPath.resolve(
              kitResolver.resolveLocalInstallerPath(distribution.getVersion(), distribution.getLicenseType(), distribution.getPackageType()));
      logger.debug("Checking if local kit is available at: {}", localInstallerPath);

      try {
        lockConcurrentInstall(localInstallerPath);
        if (!isValidLocalInstallerFilePath(false, localInstallerPath)) {
          logger.debug("Local kit at: {} invalid or absent. Downloading a fresh installer", localInstallerPath);
          kitResolver.downloadLocalInstaller(distribution.getVersion(), distribution.getLicenseType(), distribution.getPackageType(), localInstallerPath);
        }

        this.kitInstallationPath = kitResolver.resolveKitInstallationPath(distribution.getVersion(), distribution.getPackageType(), localInstallerPath, rootInstallationPath);

        if (!Files.isDirectory(this.kitInstallationPath)) {
          logger.debug("Local install not available at: {}", this.kitInstallationPath);
          kitResolver.createLocalInstallFromInstaller(distribution.getVersion(), distribution.getPackageType(), license, localInstallerPath, rootInstallationPath, env);
        }
      } finally {
        unlockConcurrentInstall(localInstallerPath);
      }
  }


    public void setupLocalInstall(License license, String kitInstallationPath, boolean offline, TerracottaCommandLineEnvironment env) {
    if (kitInstallationPath != null) {
      logger.debug("Using kitInstallationPath: {}", kitInstallationPath);
      Path path = Paths.get(kitInstallationPath);
      if (!Files.isDirectory(path)) {
        throw new IllegalArgumentException("kitInstallationPath: " + kitInstallationPath + " isn't a directory");
      }
      this.kitInstallationPath = path;
    } else if (rootInstallationPath != null) {
      Path localInstallerPath = rootInstallationPath.resolve(
          kitResolver.resolveLocalInstallerPath(distribution.getVersion(), distribution.getLicenseType(), distribution.getPackageType()));
      logger.debug("Checking if local kit is available at: {}", localInstallerPath);

      try {
        lockConcurrentInstall(localInstallerPath);
        if (!isValidLocalInstallerFilePath(offline, localInstallerPath)) {
          logger.debug("Local kit at: {} invalid or absent. Downloading a fresh installer", localInstallerPath);
          kitResolver.downloadLocalInstaller(distribution.getVersion(), distribution.getLicenseType(), distribution.getPackageType(), localInstallerPath);
        }

        this.kitInstallationPath = kitResolver.resolveKitInstallationPath(distribution.getVersion(), distribution.getPackageType(), localInstallerPath, rootInstallationPath);

        if (!Files.isDirectory(this.kitInstallationPath)) {
          logger.debug("Local install not available at: {}", this.kitInstallationPath);
          if (offline) {
            throw new IllegalArgumentException("Can not install the kit version " + distribution + " in offline mode because" +
                " the kit compressed package is not available. Please run in online mode with an internet connection.");
          }
          kitResolver.createLocalInstallFromInstaller(distribution.getVersion(), distribution.getPackageType(), license, localInstallerPath, rootInstallationPath, env);
        }
      } finally {
        unlockConcurrentInstall(localInstallerPath);
      }
    }
    if (this.kitInstallationPath != null) {
      initClientJarsMap();
      logger.debug("Local distribution is located in {}", this.kitInstallationPath);
    }
  }

  @SuppressFBWarnings("NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE")
  void unlockConcurrentInstall(Path localInstallerPath) {
    logger.debug("Thread {} unlock", Thread.currentThread().getId());
    File parent = localInstallerPath.toFile().getAbsoluteFile().getParentFile();
    File file = new File(parent, INSTALLATION_LOCK_FILE_NAME);
    try {
      org.terracotta.utilities.io.Files.delete(file.toPath());
      logger.debug("Deleted installer lock file {}", file);
    } catch (IOException e) {
      logger.error("Installer lock file {} could not be deleted", file.getAbsolutePath(), e);
    }
  }

  @SuppressWarnings("ResultOfMethodCallIgnored")
  @SuppressFBWarnings({"RV_RETURN_VALUE_IGNORED_BAD_PRACTICE", "NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE"})
  void lockConcurrentInstall(Path localInstallerPath) {
    logger.debug("Thread {} lock", Thread.currentThread().getId());

    File parent = localInstallerPath.toFile().getAbsoluteFile().getParentFile();
    parent.mkdirs();
    File file = new File(parent, INSTALLATION_LOCK_FILE_NAME);
    logger.debug("Creating Installer lock file at: {}", file);
    try {
      if (!file.createNewFile()) {
        long diff = new Date().getTime() - file.lastModified();
        if (diff > TimeUnit.MINUTES.toMillis(5)) {
          try {
            org.terracotta.utilities.io.Files.delete(file.toPath());
          } catch (IOException e) {
            logger.error("Angela Installer lock file can not be deleted when locking at {}", file.getAbsolutePath(), e);
          }
          final boolean created = file.createNewFile();
          if (!created) {
            logger.error("Angela Installer lock file can not be created at {}", file.getAbsolutePath());
          }
        }
        logger.debug("Thread {} wait", Thread.currentThread().getId());
        for (int i = 0; i < 20; i++) {
          Thread.sleep(1000);
          if (file.createNewFile()) {
            logger.debug("Thread {} pass", Thread.currentThread().getId());
            break;
          }
        }
      }
    } catch (IOException | InterruptedException e) {
      e.printStackTrace();
      logger.error("Angela Installer lock file issue at {}", file.getAbsolutePath());
    }
    logger.debug("Thread {} pass", Thread.currentThread().getId());

  }

  private void initClientJarsMap() {
    if (kitInstallationPath == null) {
      // no configured kit -> no client jars
      return;
    }

    try {
      String clientJarsRootFolderName = distribution.createDistributionController()
          .clientJarsRootFolderName(distribution);

      if (!Files.exists(kitInstallationPath.resolve(clientJarsRootFolderName))) {
        logger.warn("KIT CLIENT FOLDER '{}' IS MISSING: SKIPPING DISCOVERY OF KIT CLIENT JARS", clientJarsRootFolderName);
        return;
      }

      List<File> clientJars;
      try (Stream<Path> stream = Files.walk(kitInstallationPath.resolve(clientJarsRootFolderName))) {
        clientJars = stream
            .filter(Files::isRegularFile)
            .map(Path::toFile)
            .collect(toList());
      }

      for (File clientJar : clientJars) {
        /*
         * Identify files by reading the JAR's MANIFEST.MF file and reading the "Bundle-SymbolicName" attribute.
         * This is provided by all OSGi-enabled JARs (all TC jars do) and is meant to figure out if two JAR files
         * are providing the same thing, barring any version differences.
         * Only include jars that have their Bundle-SymbolicName start with "com.terracotta".
         */
        String bundleSymbolicName = loadManifestBundleSymbolicName(clientJar);
        if (bundleSymbolicName != null && bundleSymbolicName.startsWith("com.terracotta")) {
          this.clientJars.put(bundleSymbolicName, clientJar);
        }
      }
      logger.debug("Kit client jars : {}", this.clientJars);
    } catch (IOException ioe) {
      throw new RuntimeException("Error listing client jars in " + kitInstallationPath, ioe);
    }
  }

  @SuppressFBWarnings("NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE")
  public String getKitInstallationName() {
    return kitInstallationPath.getFileName().toString();
  }


  public File equivalentClientJar(File file) {
    String sourceBundleSymbolicName = loadManifestBundleSymbolicName(file);
    return clientJars.get(sourceBundleSymbolicName);
  }

  private String loadManifestBundleSymbolicName(File file) {
    try {
      if (file.getName().endsWith(".jar")) {
        try (JarInputStream jarInputStream = new JarInputStream(new FileInputStream(file))) {
          Manifest manifest = jarInputStream.getManifest();
          return manifest == null ? null : manifest.getMainAttributes().getValue("Bundle-SymbolicName");
        }
      } else {
        return null;
      }
    } catch (IOException ioe) {
      logger.error("Error loading the JAR manifest of " + file, ioe);
      return null;
    }
  }
}
