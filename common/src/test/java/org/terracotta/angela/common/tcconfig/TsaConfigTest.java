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

import org.junit.Test;
import org.terracotta.angela.common.net.PortAllocator;
import org.terracotta.angela.common.topology.Version;

import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @author Aurelien Broszniowski
 */
public class TsaConfigTest {

  @Test
  public void testPlatformData() {
    Version version = mock(Version.class);
    when(version.getMajor()).thenReturn(10);
    TsaConfig tsaConfig = TsaConfig.tsaConfig(version,
        TsaStripeConfig.stripe("host1", "host2")
            .data("name1", "root1")
            .data("platformData", "platformData", true)
            .persistence("name1")
    );
    final List<TcConfig> tcConfigs = tsaConfig.getTcConfigs();

    assertThat(tcConfigs.get(0).getDataDirectories().get("name1"), is("root1"));
    assertThat(tcConfigs.get(0).getDataDirectories().get("platformData"), is("platformData"));
    assertThat(tcConfigs.get(0).getPluginServices().get(0), is("name1"));
    assertThat(tcConfigs.get(0).getDataDirectories().size(), is(2));
    assertThat(tcConfigs.get(0).getPluginServices().size(), is(1));
  }

  @Test
  public void testNoPlatformData() {
    Version version = mock(Version.class);
    when(version.getMajor()).thenReturn(10);
    TsaConfig tsaConfig = TsaConfig.tsaConfig(version,
        TsaStripeConfig.stripe("host1", "host2")
            .data("name1", "root1")
            .persistence("name1")
    );
    final List<TcConfig> tcConfigs = tsaConfig.getTcConfigs();

    assertThat(tcConfigs.get(0).getDataDirectories().get("name1"), is("root1"));
    assertThat(tcConfigs.get(0).getDataDirectories().size(), is(1));
    assertThat(tcConfigs.get(0).getPluginServices().size(), is(1));
  }

  @Test
  public void testNoPersistence() {
    Version version = mock(Version.class);
    when(version.getMajor()).thenReturn(10);
    TsaConfig tsaConfig = TsaConfig.tsaConfig(version,
        TsaStripeConfig.stripe("host1", "host2")
            .data("platformData", "platformData", true)
    );
    final List<TcConfig> tcConfigs = tsaConfig.getTcConfigs();

    assertThat(tcConfigs.get(0).getDataDirectories().get("platformData"), is("platformData"));
    assertThat(tcConfigs.get(0).getDataDirectories().size(), is(1));
    assertThat(tcConfigs.get(0).getPluginServices().size(), is(0));
  }

  @Test
  public void testDataRootNoPersistence() {
    Version version = mock(Version.class);
    when(version.getMajor()).thenReturn(10);
    TsaConfig tsaConfig = TsaConfig.tsaConfig(version,
        TsaStripeConfig.stripe("host1", "host2")
            .data("data", "data1")
            .data("platformData", "platformData1", true)
    );
    final List<TcConfig> tcConfigs = tsaConfig.getTcConfigs();

    assertThat(tcConfigs.get(0).getDataDirectories().get("data"), is("data1"));
    assertThat(tcConfigs.get(0).getDataDirectories().get("platformData"), is("platformData1"));
    assertThat(tcConfigs.get(0).getDataDirectories().size(), is(2));
    assertThat(tcConfigs.get(0).getPluginServices().size(), is(0));
  }


  @Test
  public void testAddServer() {
    PortAllocator portAllocator = mock(PortAllocator.class);
    PortAllocator.PortReservation reservation = mock(PortAllocator.PortReservation.class);
    when(portAllocator.reserve(1)).thenReturn(reservation);
    when(reservation.next()).thenReturn(0);

    Version version = mock(Version.class);
    when(version.getMajor()).thenReturn(10);
    TsaConfig tsaConfig = TsaConfig.tsaConfig(version,
        TsaStripeConfig.stripe("host1", "host2").offheap("primary", "50", "GB").data("name1", "root1")
    );
    tsaConfig.initialize(portAllocator);
    final List<TcConfig> tcConfigs = tsaConfig.getTcConfigs();

    assertThat(tcConfigs.size(), equalTo(1));
    final Collection<TerracottaServer> servers = tcConfigs.get(0).getServers();
    assertThat(servers.size(), equalTo(2));
    final Iterator<TerracottaServer> iterator = servers.iterator();
    assertThat(iterator.next().getServerSymbolicName().getSymbolicName(), is("Server1-1"));
    assertThat(iterator.next().getServerSymbolicName().getSymbolicName(), is("Server1-2"));
  }

  @Test
  public void testTsaPortsRange() {
    PortAllocator portAllocator = mock(PortAllocator.class);
    PortAllocator.PortReservation reservation = mock(PortAllocator.PortReservation.class);
    when(portAllocator.reserve(1)).thenReturn(reservation);
    when(reservation.next()).thenReturn(1025);

    Version version = mock(Version.class);
    when(version.getMajor()).thenReturn(10);
    TsaConfig tsaConfig = TsaConfig.tsaConfig(version,
        TsaStripeConfig.stripe("host1", "host2").offheap("primary", "50", "GB").data("name1", "root1"),
        TsaStripeConfig.stripe("host1", "host2").offheap("primary", "50", "GB").data("name1", "root1")
    );

    tsaConfig.initialize(portAllocator);

    final List<TcConfig> tcConfigs = tsaConfig.getTcConfigs();

    for (int tcConfigIndex = 0; tcConfigIndex < 2; tcConfigIndex++) {
      for (int tcServerIndex = 0; tcServerIndex < 2; tcServerIndex++) {
        assertThat(tcConfigs.get(tcConfigIndex)
                       .getServers()
                       .get(tcServerIndex)
                       .getTsaPort() == 1025, is(true));
        assertThat(tcConfigs.get(tcConfigIndex)
                       .getServers()
                       .get(tcServerIndex)
                       .getTsaGroupPort() == 1025, is(true));
        assertThat(tcConfigs.get(tcConfigIndex)
                       .getServers()
                       .get(tcServerIndex)
                       .getJmxPort() == 0, is(true)); // jmx port config not available for OSS
        assertThat(tcConfigs.get(tcConfigIndex)
                       .getServers()
                       .get(tcServerIndex)
                       .getManagementPort() == 0, is(true));  // management port config not available for OSS

        assertThat(tcConfigs.get(tcConfigIndex)
                       .getServers()
                       .get(tcServerIndex)
                       .getTsaPort() <= 65535, is(true));
        assertThat(tcConfigs.get(tcConfigIndex)
                       .getServers()
                       .get(tcServerIndex)
                       .getTsaGroupPort() <= 65535, is(true));
        assertThat(tcConfigs.get(tcConfigIndex)
                       .getServers()
                       .get(tcServerIndex)
                       .getJmxPort() <= 65535, is(true));
        assertThat(tcConfigs.get(tcConfigIndex)
                       .getServers()
                       .get(tcServerIndex)
                       .getManagementPort() <= 65535, is(true));
      }
    }

  }

  @Test
  public void testSingleStripe() {
    Version version = mock(Version.class);
    when(version.getMajor()).thenReturn(10);
    TsaConfig tsaConfig = TsaConfig.tsaConfig(version,
        TsaStripeConfig.stripe("host1", "host2").offheap("primary", "50", "GB")
    );
    final List<TcConfig> tcConfigs = tsaConfig.getTcConfigs();
    assertThat(tcConfigs.size(), equalTo(1));
  }

  @Test
  public void testMultiStripe() {
    Version version = mock(Version.class);
    when(version.getMajor()).thenReturn(10);
    TsaConfig tsaConfig = TsaConfig.tsaConfig(version,
        TsaStripeConfig.stripe("host1", "host2").offheap("primary", "50", "GB").data("data", "root1"),
        TsaStripeConfig.stripe("host1", "host2").offheap("primary", "50", "GB")
    );
    final List<TcConfig> tcConfigs = tsaConfig.getTcConfigs();
    assertThat(tcConfigs.size(), equalTo(2));
  }

  @Test
  public void testOneTcConfig() {
    Version version = mock(Version.class);
    when(version.getMajor()).thenReturn(10);
    TsaConfig tsaConfig = TsaConfig.tsaConfig(TcConfig.tcConfig(version, getClass().getResource("/tc-config.xml")));
    final List<TcConfig> tcConfigs = tsaConfig.getTcConfigs();
    assertThat(tcConfigs.size(), equalTo(1));
  }

  @Test
  public void testMultipleTcConfigs() {
    Version version = mock(Version.class);
    when(version.getMajor()).thenReturn(10);
    TsaConfig tsaConfig = TsaConfig.tsaConfig(TcConfig.tcConfig(version, getClass().getResource("/tc-config.xml")),
        TcConfig.tcConfig(version, getClass().getResource("/tc-config.xml")));
    final List<TcConfig> tcConfigs = tsaConfig.getTcConfigs();
    assertThat(tcConfigs.size(), equalTo(2));
  }

  @Test
  public void testListOfTcConfigs() {
    Version version = mock(Version.class);
    when(version.getMajor()).thenReturn(10);
    List<TcConfig> tcConfigList = Arrays.asList(TcConfig.tcConfig(version, getClass().getResource("/tc-config.xml")),
        TcConfig.tcConfig(version, getClass().getResource("/tc-config.xml")));
    TsaConfig tsaConfig = TsaConfig.tsaConfig(tcConfigList);
    final List<TcConfig> tcConfigs = tsaConfig.getTcConfigs();
    assertThat(tcConfigs.size(), equalTo(2));
  }

}
