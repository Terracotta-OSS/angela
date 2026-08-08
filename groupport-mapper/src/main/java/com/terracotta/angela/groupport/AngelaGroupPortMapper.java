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
package com.terracotta.angela.groupport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terracotta.dynamic_config.api.model.Node;
import org.terracotta.dynamic_config.server.api.GroupPortMapper;

import java.util.HashMap;
import java.util.Map;

/**
 * Angela-provided GroupPortMapper enabling per-(thisNode, peerNode) outbound
 * group-comm routing. Reads a per-server table from the system property
 * {@value #PORT_MAP_PROPERTY}, formatted as:
 *
 *   peerName1=port1,peerName2=port2
 *
 * When the running server's mapper is asked for a (peerNode, thisNode) pair,
 * we look up peerName in the table and return the mapped port. For the self
 * lookup (peerNode == thisNode) and any peer not in the table, we return the
 * peer node's configured group port unchanged - same fallback the default
 * impl uses.
 *
 * This class is loaded by the dynamic-config server's ServiceLoader scan of
 * {@code <kit>/server/plugins/lib/}. Angela installs the bundled jar there at
 * kit-setup time and emits the {@value #PORT_MAP_PROPERTY} property in the
 * server JVM's {@code JAVA_OPTS} when net disruption is enabled.
 */
public class AngelaGroupPortMapper implements GroupPortMapper {

  public static final String PORT_MAP_PROPERTY = "angela.dynamicConfig.peerGroupPorts";

  private static final Logger LOG = LoggerFactory.getLogger(AngelaGroupPortMapper.class);

  private final Map<String, Integer> peerPorts;

  public AngelaGroupPortMapper() {
    this.peerPorts = parse(System.getProperty(PORT_MAP_PROPERTY, ""));
    LOG.info("AngelaGroupPortMapper active, per-peer overrides={}", peerPorts);
  }

  @Override
  public int getPeerGroupPort(Node peerNode, Node thisNode) {
    int defaultPort = peerNode.getGroupPort().orDefault();
    if (peerNode.getName().equals(thisNode.getName())) {
      return defaultPort;
    }
    Integer override = peerPorts.get(peerNode.getName());
    return override != null ? override : defaultPort;
  }

  private static Map<String, Integer> parse(String spec) {
    Map<String, Integer> map = new HashMap<>();
    if (spec == null || spec.isEmpty()) {
      return map;
    }
    for (String entry : spec.split(",")) {
      String trimmed = entry.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      int eq = trimmed.indexOf('=');
      if (eq <= 0 || eq == trimmed.length() - 1) {
        LOG.warn("Ignoring malformed entry in {}: '{}'", PORT_MAP_PROPERTY, trimmed);
        continue;
      }
      String key = trimmed.substring(0, eq).trim();
      String value = trimmed.substring(eq + 1).trim();
      try {
        map.put(key, Integer.parseInt(value));
      } catch (NumberFormatException e) {
        LOG.warn("Ignoring non-integer port in {}: '{}'", PORT_MAP_PROPERTY, trimmed);
      }
    }
    return map;
  }
}
