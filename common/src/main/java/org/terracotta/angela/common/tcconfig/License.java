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
package org.terracotta.angela.common.tcconfig;

import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Created by esebasti on 7/21/17.
 */
public class License implements Serializable {
  private static final long serialVersionUID = 1L;

  private final String licenseContent;
  private final String filename;

  public License(URL licensePath) {
    this.filename = new File(licensePath.getFile()).getName();
    try (InputStream is = licensePath.openStream()) {
      if (is == null) {
        throw new IllegalArgumentException("License file is not present");
      }
      licenseContent = IOUtils.toString(is, StandardCharsets.UTF_8);
    } catch (IOException ioe) {
      throw new RuntimeException(ioe);
    }
  }

  public File writeToFile(File dest) {
    File licenseFile = new File(dest, this.filename);
    // tests are concurrently using a kit so do not re-write a license file
    if (!licenseFile.exists()) {
      try {
        Files.write(licenseFile.toPath(), licenseContent.getBytes(StandardCharsets.UTF_8));
        licenseFile.setWritable(true);
      } catch (IOException ioe) {
        throw new RuntimeException(ioe);
      }
    } else {
      licenseFile.setWritable(true);
    }
    return licenseFile;
  }

  public String getFilename() {
    return filename;
  }
}
