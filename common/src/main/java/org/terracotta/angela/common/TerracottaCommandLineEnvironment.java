/*
 * The contents of this file are subject to the Terracotta Public License Version
 * 2.0 (the "License"); You may not use this file except in compliance with the
 * License. You may obtain a copy of the License at
 *
 * http://terracotta.org/legal/terracotta-public-license.
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License for
 * the specific language governing rights and limitations under the License.
 *
 * The Covered Software is Angela.
 *
 * The Initial Developer of the Covered Software is
 * Terracotta, Inc., a Software AG company
 */

package org.terracotta.angela.common;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

import static java.util.Arrays.asList;
import static java.util.Collections.singleton;
import static java.util.Collections.unmodifiableSet;
import static java.util.Objects.requireNonNull;
import static org.terracotta.angela.common.AngelaProperties.JAVA_OPTS;
import static org.terracotta.angela.common.AngelaProperties.JAVA_VENDOR;
import static org.terracotta.angela.common.AngelaProperties.JAVA_VERSION;

/**
 * Instances of this class are immutable.
 */
public class TerracottaCommandLineEnvironment {
  public static final TerracottaCommandLineEnvironment DEFAULT;

  static {
    String version = JAVA_VERSION.getValue();
    Set<String> vendors = JAVA_VENDOR.getValue().equals("") ? new HashSet<>() : singleton(JAVA_VENDOR.getValue());
    Set<String> opts = JAVA_OPTS.getValue().equals("") ? new HashSet<>() : singleton(JAVA_OPTS.getValue());
    DEFAULT = new TerracottaCommandLineEnvironment(version, vendors, opts);
  }

  private String javaVersion;
  private Set<String> javaVendors;
  private Set<String> javaOpts;

  /**
   * Create a new instance that contains whatever is necessary to build a JVM command line, minus classpath and main class.
   *
   * @param javaVersion the java version specified in toolchains.xml, can be empty if any version will fit.
   * @param javaVendors a set of acceptable java vendors specified in toolchains.xml, can be empty if any vendor will fit.
   * @param javaOpts    some command line arguments to give to the JVM, like -Xmx2G, -XX:Whatever or -Dsomething=abc.
   *                    Can be empty if no JVM argument is needed.
   */
  private TerracottaCommandLineEnvironment(String javaVersion, Set<String> javaVendors, Set<String> javaOpts) {
    validate(javaVersion, javaVendors, javaOpts);
    this.javaVersion = javaVersion;
    this.javaVendors = unmodifiableSet(new HashSet<>(javaVendors));
    this.javaOpts = unmodifiableSet(new HashSet<>(javaOpts));
  }

  private void validate(String javaVersion, Set<String> javaVendors, Set<String> javaOpts) {
    requireNonNull(javaVersion);
    requireNonNull(javaVendors);
    requireNonNull(javaOpts);

    if(!javaVendors.isEmpty()) {
      javaVendors.stream()
          .filter(((Predicate<String>) vendor -> vendor == null || vendor.isEmpty()).negate())
          .findFirst()
          .orElseThrow(() -> new IllegalArgumentException("None of the java vendors can be null or empty"));
    }
    javaOpts.stream()
        .filter(((Predicate<String>) opt -> opt == null || opt.isEmpty()).negate())
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("None of the java opts can be null or empty"));
  }

  public TerracottaCommandLineEnvironment withJavaVersion(String javaVersion) {
    return new TerracottaCommandLineEnvironment(javaVersion, javaVendors, javaOpts);
  }

  public TerracottaCommandLineEnvironment withJavaVendors(String... javaVendors) {
    return new TerracottaCommandLineEnvironment(javaVersion, new HashSet<>(asList(javaVendors)), javaOpts);
  }

  public TerracottaCommandLineEnvironment withJavaOpts(String... javaOpts) {
    return new TerracottaCommandLineEnvironment(javaVersion, javaVendors, new HashSet<>(asList(javaOpts)));
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

  @Override
  public String toString() {
    return "TerracottaCommandLineEnvironment{" +
        "javaVersion='" + javaVersion + '\'' +
        ", javaVendors=" + javaVendors +
        ", javaOpts=" + javaOpts +
        '}';
  }
}
