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
package org.terracotta.dynamic_config.server.api;

import org.terracotta.dynamic_config.api.model.Node;

/**
 * Compile-only stub of the dynamic-config server SPI's GroupPortMapper.
 *
 * The kit's real interface lives in dynamic-config-server-api-X.jar inside
 * {@code <kit>/server/plugins/api/}. We deliberately do NOT depend on that
 * jar (it isn't a published Maven artifact); instead we compile against
 * this stub with byte-compatible signatures, then exclude this class from
 * the produced jar (see groupport-mapper/pom.xml) so the kit's real
 * interface is the only one on the runtime classpath.
 */
public interface GroupPortMapper {
  int getPeerGroupPort(Node peerNode, Node thisNode);
}
