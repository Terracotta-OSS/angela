/*
 * Copyright Terracotta, Inc.
 * Copyright Super iPaaS Integration LLC, an IBM Company 2024
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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terracotta.angela.agent.Agent;
import org.terracotta.angela.common.distribution.Distribution;
import org.terracotta.angela.common.tcconfig.License;
import org.terracotta.angela.common.topology.InstanceId;
import org.terracotta.angela.common.util.FileUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static org.terracotta.angela.common.AngelaProperties.KIT_COPY;
import static org.terracotta.angela.common.util.IpUtils.areAllLocal;
import static org.terracotta.utilities.io.Files.ExtendedOption.RECURSIVE;

/**
 * Download the kit tarball from Kratos
 *
 * @author Aurelien Broszniowski
 */
public class RemoteKitManager extends KitManager {
  private static final Logger logger = LoggerFactory.getLogger(RemoteKitManager.class);

  private final Path kitPath; // The location containing server logs

  public RemoteKitManager(InstanceId instanceId, Distribution distribution, String kitInstallationName) {
    super(distribution);
    this.kitInstallationPath = rootInstallationPath.resolve(kitInstallationName);
    Path workingPath = Agent.WORK_DIR.resolve(instanceId.toString());
    logger.debug("Working directory is: {}", workingPath);
    this.kitPath = workingPath;
  }

  // Returns the location to be used for kit - could be the source kit path itself, or a new location based on if or not
  // the kit was copied
  public File installKit(License license, Collection<String> serversHostnames) {
    try {
      Files.createDirectories(kitPath);

      logger.debug("should copy a separate kit install ? {}", KIT_COPY.getBooleanValue());
      if (areAllLocal(serversHostnames) && !KIT_COPY.getBooleanValue()) {
        logger.debug("Skipped copying kit from {} to {}", kitInstallationPath.toAbsolutePath(), kitPath);
        if (license != null) {
          license.writeToFile(kitPath.toFile());
        }
        return kitInstallationPath.toFile();
      } else {
        logger.debug("Copying {} to {}", kitInstallationPath.toAbsolutePath(), kitPath);
        FileUtils.copy(kitInstallationPath, kitPath, REPLACE_EXISTING, RECURSIVE);
        if (license != null) {
          license.writeToFile(kitPath.toFile());
        }
        return kitPath.toFile();
      }
    } catch (IOException e) {
      throw new RuntimeException("Can not create working install", e);
    }
  }

  public boolean isKitAvailable() {
    logger.debug("verifying if the extracted kit is already available locally to setup an install");
    if (!Files.isDirectory(kitInstallationPath)) {
      logger.debug("Local kit installation is not available");
      return false;
    }
    return true;
  }

  public void deleteInstall(File installLocation) {
    logger.debug("Deleting installation in {}", installLocation.getAbsolutePath());
    FileUtils.deleteQuietly(installLocation.toPath());
  }
}
