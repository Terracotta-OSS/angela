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
package org.terracotta.angela.common.tcconfig.holders;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

/**
 * @author vmad
 */
public class TcConfig10HolderTest {
  private static final String LINE_SEP = System.lineSeparator();

  @Test
  public void testUpdateSecurityRootDirectoryLocation() throws Exception {
    try (InputStream tcConfigStream = TcConfig10HolderTest.class.getResourceAsStream("/tc-config.xml")) {
      TcConfig10Holder tcConfig10Holder = new TcConfig10Holder(tcConfigStream);
      assertThat(tcConfig10Holder.getSecurityRootDirectory(), is("initial_path"));

      String NEW_PATH_VALUE = "new_path";
      tcConfig10Holder.updateSecurityRootDirectoryLocation(NEW_PATH_VALUE);
      assertThat(tcConfig10Holder.getSecurityRootDirectory(), is(NEW_PATH_VALUE));
    }
  }

  @Test
  public void testCreateTcPropertyWhenThereIsNoProperty() {
    String cfg =
        "<tc-config xmlns=\"http://www.terracotta.org/config\">" + LINE_SEP +
        "  <plugins>" + LINE_SEP +
        "    <service xmlns:sec=\"http://www.terracottatech.com/config/security\">" + LINE_SEP +
        "      <sec:security>" + LINE_SEP +
        "        <sec:security-root-directory>initial_path</sec:security-root-directory>" + LINE_SEP +
        "        <sec:ssl-tls/>" + LINE_SEP +
        "      </sec:security>" + LINE_SEP +
        "    </service>" + LINE_SEP +
        "  </plugins>" + LINE_SEP +
        "</tc-config>";
    TcConfig10Holder tcConfig10Holder = new TcConfig10Holder(new ByteArrayInputStream(cfg.getBytes()));
    tcConfig10Holder.createOrUpdateTcProperty("a", "new");
    tcConfig10Holder.createOrUpdateTcProperty("b", "new2");
    assertThat(tcConfig10Holder.tcConfigContent, is(
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>" +
            "<tc-config xmlns=\"http://www.terracotta.org/config\">" + LINE_SEP +
            "  <plugins>" + LINE_SEP +
            "    <service xmlns:sec=\"http://www.terracottatech.com/config/security\">" + LINE_SEP +
            "      <sec:security>" + LINE_SEP +
            "        <sec:security-root-directory>initial_path</sec:security-root-directory>" + LINE_SEP +
            "        <sec:ssl-tls/>" + LINE_SEP +
            "      </sec:security>" + LINE_SEP +
            "    </service>" + LINE_SEP +
            "  </plugins>" + LINE_SEP +
            "  <tc-properties>" + LINE_SEP +
            "    <property name=\"a\" value=\"new\"/>" + LINE_SEP +
            "    <property name=\"b\" value=\"new2\"/>" + LINE_SEP +
            "  </tc-properties>" + LINE_SEP +
            "</tc-config>"
    ));
  }

  @Test
  public void testCreateTcPropertyWhenThereIsAtLeastOneProperty() {
    String cfg =
        "<tc-config xmlns=\"http://www.terracotta.org/config\">" + LINE_SEP +
        "  <plugins>" + LINE_SEP +
        "    <service xmlns:sec=\"http://www.terracottatech.com/config/security\">" + LINE_SEP +
        "      <sec:security>" + LINE_SEP +
        "        <sec:security-root-directory>initial_path</sec:security-root-directory>" + LINE_SEP +
        "        <sec:ssl-tls/>" + LINE_SEP +
        "      </sec:security>" + LINE_SEP +
        "    </service>" + LINE_SEP +
        "  </plugins>" + LINE_SEP +
        "  <tc-properties>" + LINE_SEP +
        "    <property name=\"z\" value=\"unrelated\"/>" + LINE_SEP +
        "  </tc-properties>" + LINE_SEP +
        "</tc-config>";
    TcConfig10Holder tcConfig10Holder = new TcConfig10Holder(new ByteArrayInputStream(cfg.getBytes()));
    tcConfig10Holder.createOrUpdateTcProperty("a", "new");
    tcConfig10Holder.createOrUpdateTcProperty("b", "new2");
    assertThat(tcConfig10Holder.tcConfigContent, is(
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>" +
            "<tc-config xmlns=\"http://www.terracotta.org/config\">" + LINE_SEP +
            "  <plugins>" + LINE_SEP +
            "    <service xmlns:sec=\"http://www.terracottatech.com/config/security\">" + LINE_SEP +
            "      <sec:security>" + LINE_SEP +
            "        <sec:security-root-directory>initial_path</sec:security-root-directory>" + LINE_SEP +
            "        <sec:ssl-tls/>" + LINE_SEP +
            "      </sec:security>" + LINE_SEP +
            "    </service>" + LINE_SEP +
            "  </plugins>" + LINE_SEP +
            "  <tc-properties>" + LINE_SEP +
            "    <property name=\"z\" value=\"unrelated\"/>" + LINE_SEP +
            "    <property name=\"a\" value=\"new\"/>" + LINE_SEP +
            "    <property name=\"b\" value=\"new2\"/>" + LINE_SEP +
            "  </tc-properties>" + LINE_SEP +
            "</tc-config>"
    ));
  }

  @Test
  public void testUpdateTcProperty() {
    String cfg =
        "<tc-config xmlns=\"http://www.terracotta.org/config\">" + LINE_SEP +
        "  <plugins>" + LINE_SEP +
        "    <service xmlns:sec=\"http://www.terracottatech.com/config/security\">" + LINE_SEP +
        "      <sec:security>" + LINE_SEP +
        "        <sec:security-root-directory>initial_path</sec:security-root-directory>" + LINE_SEP +
        "        <sec:ssl-tls/>" + LINE_SEP +
        "      </sec:security>" + LINE_SEP +
        "    </service>" + LINE_SEP +
        "  </plugins>" + LINE_SEP +
        "  <tc-properties>" + LINE_SEP +
        "    <property name=\"a\" value=\"old\"/>" + LINE_SEP +
        "  </tc-properties>" + LINE_SEP +
        "</tc-config>";
    TcConfig10Holder tcConfig10Holder = new TcConfig10Holder(new ByteArrayInputStream(cfg.getBytes()));
    tcConfig10Holder.createOrUpdateTcProperty("a", "new");
    tcConfig10Holder.createOrUpdateTcProperty("b", "new2");
    assertThat(tcConfig10Holder.tcConfigContent, is(
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>" +
            "<tc-config xmlns=\"http://www.terracotta.org/config\">" + LINE_SEP +
            "  <plugins>" + LINE_SEP +
            "    <service xmlns:sec=\"http://www.terracottatech.com/config/security\">" + LINE_SEP +
            "      <sec:security>" + LINE_SEP +
            "        <sec:security-root-directory>initial_path</sec:security-root-directory>" + LINE_SEP +
            "        <sec:ssl-tls/>" + LINE_SEP +
            "      </sec:security>" + LINE_SEP +
            "    </service>" + LINE_SEP +
            "  </plugins>" + LINE_SEP +
            "  <tc-properties>" + LINE_SEP +
            "    <property name=\"a\" value=\"new\"/>" + LINE_SEP +
            "    <property name=\"b\" value=\"new2\"/>" + LINE_SEP +
            "  </tc-properties>" + LINE_SEP +
            "</tc-config>"
    ));
  }
}