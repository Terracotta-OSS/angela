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
package org.terracotta.angela.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terracotta.angela.common.util.JDK;
import org.terracotta.angela.common.util.JavaBinaries;
import org.terracotta.angela.common.util.JavaLocationResolver;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static java.util.Arrays.asList;
import static java.util.Collections.emptySet;
import static java.util.Collections.singleton;
import static java.util.Collections.unmodifiableSet;
import static java.util.Objects.requireNonNull;
import static org.terracotta.angela.common.AngelaProperties.JAVA_HOME;
import static org.terracotta.angela.common.AngelaProperties.JAVA_OPTS;
import static org.terracotta.angela.common.AngelaProperties.JAVA_RESOLVER;
import static org.terracotta.angela.common.AngelaProperties.JAVA_VENDOR;
import static org.terracotta.angela.common.AngelaProperties.JAVA_VERSION;

/**
 * Instances of this class are immutable.
 */
public class TerracottaCommandLineEnvironment {
  private final static Logger LOGGER = LoggerFactory.getLogger(TerracottaCommandLineEnvironment.class);

  public static final TerracottaCommandLineEnvironment DEFAULT;

  static {
    switch (JAVA_RESOLVER.getValue()) {
      case "toolchain": {
        String version = JAVA_VERSION.getValue();
        // Important - Use a LinkedHashSet to preserve the order of preferred Java vendor
        Set<String> vendors = JAVA_VENDOR.getValue().equals("") ? new LinkedHashSet<>() : singleton(JAVA_VENDOR.getValue());
        // Important - Use a LinkedHashSet to preserve the order of opts, as some opts are position-sensitive
        Set<String> opts = JAVA_OPTS.getValue().equals("") ? new LinkedHashSet<>() : singleton(JAVA_OPTS.getValue());
        DEFAULT = new TerracottaCommandLineEnvironment(version, vendors, opts);
        break;
      }
      case "user": {
        Set<String> opts = JAVA_OPTS.getValue().equals("") ? new LinkedHashSet<>() : singleton(JAVA_OPTS.getValue());
        DEFAULT = new TerracottaCommandLineEnvironment(Paths.get(JAVA_HOME.getValue()), "", emptySet(), opts);
        break;
      }
      default:
        throw new AssertionError("Unsupported value for '" + JAVA_RESOLVER.getPropertyName() + "': " + JAVA_RESOLVER.getValue());
    }
  }

  private final Path javaHome;
  private final String javaVersion;
  private final Set<String> javaVendors;
  private final Set<String> javaOpts;
  private final JavaLocationResolver javaLocationResolver;

  /**
   * Create a new instance that contains whatever is necessary to build a JVM command line, minus classpath and main class.
   *
   * @param javaVersion the java version specified in toolchains.xml, can be empty if any version will fit.
   * @param javaVendors a set of acceptable java vendors specified in toolchains.xml, can be empty if any vendor will fit.
   * @param javaOpts    some command line arguments to give to the JVM, like -Xmx2G, -XX:Whatever or -Dsomething=abc.
   *                    Can be empty if no JVM argument is needed.
   */
  private TerracottaCommandLineEnvironment(Path javaHome, String javaVersion, Set<String> javaVendors, Set<String> javaOpts) {
    validate(javaVersion, javaVendors, javaOpts);
    this.javaHome = javaHome;
    this.javaVersion = javaVersion;
    this.javaVendors = unmodifiableSet(new LinkedHashSet<>(javaVendors));
    this.javaOpts = unmodifiableSet(new LinkedHashSet<>(javaOpts));
    // javaHome ? "user" resolver. Otherwise: "toolchain" resolver
    this.javaLocationResolver = javaHome != null ? null : new JavaLocationResolver();
  }

  private TerracottaCommandLineEnvironment(String javaVersion, Set<String> javaVendors, Set<String> javaOpts) {
    this(null, javaVersion, javaVendors, javaOpts);
  }

  private static void validate(String javaVersion, Set<String> javaVendors, Set<String> javaOpts) {
    requireNonNull(javaVersion);
    requireNonNull(javaVendors);
    requireNonNull(javaOpts);

    if (javaVendors.stream().anyMatch(vendor -> vendor == null || vendor.isEmpty())) {
      throw new IllegalArgumentException("None of the java vendors can be null or empty");
    }

    if (javaOpts.stream().anyMatch(opt -> opt == null || opt.isEmpty())) {
      throw new IllegalArgumentException("None of the java opts can be null or empty");
    }
  }

  public TerracottaCommandLineEnvironment withJavaVersion(String javaVersion) {
    if (javaHome != null) {
      throw new UnsupportedOperationException("Unable to change the Java version when using the 'user' resolver");
    }
    return new TerracottaCommandLineEnvironment(null, javaVersion, javaVendors, javaOpts);
  }

  public TerracottaCommandLineEnvironment withJavaVendors(String... javaVendors) {
    if (javaHome != null) {
      throw new UnsupportedOperationException("Unable to change the Java vendors when using the 'user' resolver");
    }
    return new TerracottaCommandLineEnvironment(null, javaVersion, new LinkedHashSet<>(asList(javaVendors)), javaOpts);
  }

  public TerracottaCommandLineEnvironment withJavaOpts(String... javaOpts) {
    return new TerracottaCommandLineEnvironment(javaHome, javaVersion, javaVendors, new LinkedHashSet<>(asList(javaOpts)));
  }

  public TerracottaCommandLineEnvironment withCurrentJavaHome() {
    return withJavaHome(JavaBinaries.javaHome());
  }

  public TerracottaCommandLineEnvironment withJavaHome(Path jdkHome) {
    return new TerracottaCommandLineEnvironment(requireNonNull(jdkHome), "", emptySet(), javaOpts);
  }

  public Path getJavaHome() {
    return Optional.ofNullable(javaHome).orElseGet(() -> {
      List<JDK> jdks = javaLocationResolver.resolveJavaLocations(getJavaVersion(), getJavaVendors(), true);
      if (jdks.size() > 1) {
        LOGGER.warn("Multiple matching java versions found: {} - using the 1st one", jdks);
      }
      return jdks.get(0).getHome();
    });
  }

  public String getJavaVersion() {
    return javaVersion;
  }

  public Set<String> getJavaVendors() {
    return javaVendors;
  }

  public Set<String> getJavaOpts() {
    return javaOpts;
  }

  public Map<String, String> buildEnv(Map<String, String> overrides) {
    LOGGER.info("overrides={}", overrides);

    Map<String, String> env = new HashMap<>();
    Path javaHome = getJavaHome();
    env.put("JAVA_HOME", javaHome.toString());

    Set<String> javaOpts = getJavaOpts();
    if (!javaOpts.isEmpty()) {
      String joinedJavaOpts = String.join(" ", javaOpts);
      env.put("JAVA_OPTS", joinedJavaOpts);
    }

    for (Map.Entry<String, String> entry : overrides.entrySet()) {
      if (entry.getValue() == null) {
        env.remove(entry.getKey()); // ability to clear an existing entry
      } else {
        env.put(entry.getKey(), entry.getValue());
      }
    }

    Stream.of("JAVA_HOME", "JAVA_OPTS").forEach(key -> LOGGER.info(" {} = {}", key, env.get(key)));

    return env;
  }

  @Override
  public String toString() {
    return "TerracottaCommandLineEnvironment{" +
        "javaVersion='" + javaVersion + '\'' +
        ", javaVendors=" + javaVendors +
        ", javaOpts=" + javaOpts +
        '}';
  }
}
