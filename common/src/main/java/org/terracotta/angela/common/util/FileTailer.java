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
package org.terracotta.angela.common.util;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/**
 * @author Aurelien Broszniowski
 */

public class FileTailer {

  public static void tailFile(String filePath, TriggeringOutputStream triggeringOutput) {
    Thread tailerThread = new Thread(() -> {
      File file = new File(filePath);
      int timeoutCount = 0;
      while (!file.exists() && timeoutCount < 30) {
        try {
          Thread.sleep(1000);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          return;
        }
        timeoutCount++;
      }
      if (timeoutCount >= 30) {
        throw new RuntimeException("Exception when trying to open the server log file " + filePath);
      }

      try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
        long filePointer = 0L;

        while (!Thread.currentThread().isInterrupted()) {
          long fileLength = raf.length();
          if (fileLength < filePointer) {
            filePointer = fileLength;
          }
          if (fileLength > filePointer) {
            raf.seek(filePointer);
            String rawLine;
            while ((rawLine = raf.readLine()) != null) {
              String line = new String(rawLine.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
              triggeringOutput.processLine(line);
            }
            filePointer = raf.getFilePointer();
          }
          Thread.sleep(500);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } catch (Exception e) {
        throw new RuntimeException("Exception when file tailing the server log", e);
      }
    }, "FileTailer-Thread");

    tailerThread.setDaemon(true);
    tailerThread.start();
  }

}
