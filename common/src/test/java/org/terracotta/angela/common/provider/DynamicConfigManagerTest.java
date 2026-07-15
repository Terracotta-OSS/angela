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
package org.terracotta.angela.common.provider;

import org.junit.Test;
import org.terracotta.angela.common.net.DisruptionProvider;
import org.terracotta.angela.common.net.Disruptor;
import org.terracotta.angela.common.net.PortAllocator;
import org.terracotta.angela.common.tcconfig.ServerSymbolicName;
import org.terracotta.angela.common.tcconfig.TerracottaServer;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.terracotta.angela.common.dynamic_cluster.Stripe.stripe;
import static org.terracotta.angela.common.provider.DynamicConfigManager.dynamicCluster;
import static org.terracotta.angela.common.tcconfig.TerracottaServer.server;

public class DynamicConfigManagerTest {
  @Test
  public void createsAnAdvertisedServerGroupProxyForDynamicConfigDisruption() {
    TerracottaServer server1 = server("server-1", "localhost").tsaPort(9510).tsaGroupPort(9610);
    TerracottaServer server2 = server("server-2", "localhost").tsaPort(9511).tsaGroupPort(9611);
    TerracottaServer server3 = server("server-3", "localhost").tsaPort(9512).tsaGroupPort(9612);
    DynamicConfigManager manager = dynamicCluster(stripe(server1, server2, server3));
    RecordingDisruptionProvider disruptionProvider = new RecordingDisruptionProvider();
    Map<ServerSymbolicName, Disruptor> disruptionLinks = new HashMap<>();
    Map<ServerSymbolicName, Integer> proxiedPorts = new HashMap<>();

    manager.createDisruptionLinks(server1, disruptionProvider, disruptionLinks, proxiedPorts, new IncrementingPortAllocator(9810));

    assertThat(server1.getTsaGroupPort(), is(9810));
    assertThat(proxiedPorts.get(server1.getServerSymbolicName()), is(9610));
    assertThat(disruptionProvider.source, is(new InetSocketAddress("localhost", 9810)));
    assertThat(disruptionProvider.destination, is(new InetSocketAddress("localhost", 9610)));
    assertThat(disruptionLinks.get(server2.getServerSymbolicName()), is(disruptionProvider.disruptor));
    assertThat(disruptionLinks.get(server3.getServerSymbolicName()), is(disruptionProvider.disruptor));
  }

  private static class RecordingDisruptionProvider implements DisruptionProvider {
    private final Disruptor disruptor = new NoopDisruptor();
    private InetSocketAddress source;
    private InetSocketAddress destination;

    @Override
    public boolean isProxyBased() {
      return true;
    }

    @Override
    public void removeLink(Disruptor disruptor) {
    }

    @Override
    public Disruptor createLink(InetSocketAddress src, InetSocketAddress dest) {
      this.source = src;
      this.destination = dest;
      return disruptor;
    }
  }

  private static class NoopDisruptor implements Disruptor {
    @Override
    public void disrupt() {
    }

    @Override
    public void undisrupt() {
    }

    @Override
    public void close() {
    }
  }

  private static class IncrementingPortAllocator implements PortAllocator {
    private final AtomicInteger nextPort;

    private IncrementingPortAllocator(int firstPort) {
      this.nextPort = new AtomicInteger(firstPort);
    }

    @Override
    public PortReservation reserve(int portCounts) {
      return new PortReservation() {
        private int remaining = portCounts;

        @Override
        public void close() {
        }

        @Override
        public boolean hasNext() {
          return remaining > 0;
        }

        @Override
        public Integer next() {
          if (remaining <= 0) {
            throw new NoSuchElementException();
          }
          remaining--;
          return nextPort.getAndIncrement();
        }
      };
    }

    @Override
    public void close() {
    }
  }
}
