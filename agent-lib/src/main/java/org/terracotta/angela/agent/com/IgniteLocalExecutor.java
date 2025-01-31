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
package org.terracotta.angela.agent.com;

import org.apache.ignite.Ignite;
import org.apache.ignite.configuration.CollectionConfiguration;
import org.apache.ignite.lang.IgniteCallable;
import org.apache.ignite.lang.IgniteRunnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terracotta.angela.agent.Agent;
import org.terracotta.angela.agent.client.RemoteClientManager;
import org.terracotta.angela.agent.kit.RemoteKitManager;
import org.terracotta.angela.common.clientconfig.ClientId;
import org.terracotta.angela.common.cluster.Cluster;
import org.terracotta.angela.common.distribution.Distribution;
import org.terracotta.angela.common.topology.InstanceId;
import org.terracotta.angela.common.util.IpUtils;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static java.util.function.Predicate.isEqual;

/**
 * Executor which is using only one local ignite instance, plus eventually one per client job
 */
public class IgniteLocalExecutor implements Executor {
  private final static Logger logger = LoggerFactory.getLogger(IgniteLocalExecutor.class);

  protected final UUID group;
  protected final Ignite ignite;
  protected final AgentID agentID;
  protected final IgniteAgentGroup agentGroup;

  public IgniteLocalExecutor(Agent agent) {
    this(agent.getGroupId(), agent.getAgentID(), agent.getIgnite());
  }

  public IgniteLocalExecutor(UUID group, AgentID agentID, Ignite ignite) {
    this.group = group;
    this.agentID = agentID;
    this.ignite = ignite;
    this.agentGroup = new IgniteAgentGroup(group, agentID, ignite);
  }

  public Ignite getIgnite() {
    return ignite;
  }

  @Override
  public void close() {
    CompletableFuture<Void> future = CompletableFuture.allOf(agentGroup.getSpawnedAgents().parallelStream()
        .filter(isEqual(getLocalAgentID()).negate())
        .map(this::shutdown)
        .filter(Optional::isPresent)
        .map(Optional::get)
        .toArray(CompletableFuture[]::new));
    Duration timeout = Duration.ofSeconds(30L);
    try {
      future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    } catch (ExecutionException e) {
      // impossible to go there
      throw new AssertionError(e.getCause());
    } catch (TimeoutException e) {
      logger.warn("Some agents did not shutdown within {}: {}", timeout, agentGroup.getSpawnedAgents(), e);
    }
  }

  @Override
  public void uploadClientJars(AgentID agentID, InstanceId instanceId, List<Path> locations) {
    Future<Void> remoteDownloadFuture = executeAsync(agentID, () -> downloadFiles(instanceId, new RemoteClientManager(instanceId).getClientClasspathRoot()));
    uploadFiles(instanceId, locations, remoteDownloadFuture);
  }

  @Override
  public void uploadKit(AgentID agentID, InstanceId instanceId, Distribution distribution, String kitInstallationName, Path kitInstallationPath) {
    Future<Void> remoteDownloadFuture = executeAsync(agentID, () -> {
      RemoteKitManager remoteKitManager = new RemoteKitManager(instanceId, distribution, kitInstallationName);
      Path installDir = remoteKitManager.getKitInstallationPath().getParent();
      downloadFiles(instanceId, installDir);
    });
    uploadFiles(instanceId, Collections.singletonList(kitInstallationPath), remoteDownloadFuture);
  }

  @Override
  public Optional<CompletableFuture<Void>> shutdown(AgentID agentID) {
    if (getLocalAgentID().equals(agentID)) {
      throw new IllegalArgumentException("Cannot kill myself: " + agentID);
    }
    return agentGroup.requestShutdown(agentID);
  }

  @Override
  public String toString() {
    return getLocalAgentID().toString();
  }

  @Override
  public AgentID getLocalAgentID() {
    return agentID;
  }

  @Override
  public synchronized Optional<AgentID> findAgentID(String hostname) {
    return Optional.ofNullable(IpUtils.isLocal(hostname) ?
        getLocalAgentID() : // requested hostname is local (i.e. 127.0.0.1, localhost, etc) and then we would use the started orchestrator for it
        agentGroup.findRemoteAgentID(hostname).orElse(null));
  }

  @Override
  public synchronized AgentGroup getGroup() {
    return agentGroup;
  }

  @Override
  public Cluster getCluster() {
    return new Cluster(ignite, agentID, null);
  }

  @Override
  public Cluster getCluster(ClientId clientId) {
    return new Cluster(ignite, agentID, clientId);
  }

  @Override
  public Future<Void> executeAsync(AgentID agentID, IgniteRunnable job) {
    logger.debug("Executing job on: {}", agentID);
    return agentGroup.clusterGroup(agentID)
        .map(clusterGroup -> new IgniteFutureAdapter<>(agentID, ignite.compute(clusterGroup).runAsync(job)))
        .orElseThrow(() -> new IllegalArgumentException("No agent found matching: " + agentID + " in group " + group));
  }

  @Override
  public <R> Future<R> executeAsync(AgentID agentID, IgniteCallable<R> job) {
    logger.debug("Executing job on: {}", agentID);
    return agentGroup.clusterGroup(agentID)
        .map(clusterGroup -> new IgniteFutureAdapter<>(agentID, ignite.compute(clusterGroup).callAsync(job)))
        .orElseThrow(() -> new IllegalArgumentException("No agent found matching: " + agentID + " in group " + group));
  }

  @Override
  public BlockingQueue<FileTransfer> getFileTransferQueue(InstanceId instanceId) {
    return ignite.queue(instanceId + "@file-transfer-queue", 500, new CollectionConfiguration().setGroupName(group.toString()));
  }

  @Override
  public Optional<AgentID> startRemoteAgent(String hostname) {
    // we do not use SSH to spawn remote agents: all remote hostnames would be handled from local ignite
    agentGroup.joined(getLocalAgentID(), hostname);
    return Optional.empty();
  }
}
