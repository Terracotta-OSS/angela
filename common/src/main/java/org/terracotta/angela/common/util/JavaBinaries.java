/*
 * Copyright Terracotta, Inc.
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * @author Mathieu Carbou
 */
public class JavaBinaries {

  private static final boolean WIN = System.getProperty("os.name", "").toLowerCase().contains("win");

  public static Path javaHome() {
    return Paths.get(System.getProperty("java.home"));
  }

  public static Optional<Path> jdkHome() {
    return jdkOf(javaHome());
  }

  public static Optional<Path> jdkOf(Path javaHome) {
    return Stream.of(javaHome, javaHome.getParent())
        .map(home -> home.resolve("bin").resolve(bin("javac")))
        .filter(Files::exists)
        .map(p -> p.getParent().getParent())
        .findFirst();
  }

  public static Optional<Path> find(String name) {
    return find(name, javaHome());
  }

  public static Optional<Path> find(String name, Path javaHome) {
    return jdkOf(javaHome).map(jdkHome -> Stream.of(javaHome, jdkHome)).orElse(Stream.of(javaHome))
        .map(home -> home.resolve("bin").resolve(bin(name)))
        .filter(Files::exists)
        .findFirst();
  }

  public static String bin(String name) {
    return WIN ? name + ".exe" : name;
  }
}
