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
package org.terracotta.angela.agent.com.grid.hazelcast;

import com.hazelcast.cluster.Member;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.IExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terracotta.angela.agent.client.RemoteClientManager;
import org.terracotta.angela.agent.com.AgentGroup;
import org.terracotta.angela.agent.com.AgentID;
import org.terracotta.angela.agent.com.Executor;
import org.terracotta.angela.agent.com.FileTransfer;
import org.terracotta.angela.agent.com.RemoteCallable;
import org.terracotta.angela.agent.com.RemoteRunnable;
import org.terracotta.angela.agent.com.grid.ClusterPrimitives;
import org.terracotta.angela.agent.kit.RemoteKitManager;
import org.terracotta.angela.common.clientconfig.ClientId;
import org.terracotta.angela.common.cluster.Cluster;
import org.terracotta.angela.common.distribution.Distribution;
import org.terracotta.angela.common.topology.InstanceId;
import org.terracotta.angela.common.util.FileUtils;
import org.terracotta.angela.common.util.IpUtils;

import java.io.IOException;
import java.io.Serializable;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static java.util.function.Predicate.isEqual;

/**
 * Executor backed by Hazelcast's {@link IExecutorService}.
 * Equivalent to {@code IgniteLocalExecutor} for the Hazelcast grid backend.
 */
public class HazelcastExecutor implements Executor {
  private static final Logger logger = LoggerFactory.getLogger(HazelcastExecutor.class);

  private static final String EXECUTOR_SERVICE_NAME = "angela-compute";

  protected final UUID group;
  protected final HazelcastInstance hz;
  protected final AgentID agentID;
  protected final HazelcastAgentGroup agentGroup;
  protected final ClusterPrimitives clusterPrimitives;

  public HazelcastExecutor(UUID group, AgentID agentID, HazelcastInstance hz) {
    this(group, agentID, hz, new HazelcastClusterPrimitives(hz));
  }

  public HazelcastExecutor(UUID group, AgentID agentID, HazelcastInstance hz, ClusterPrimitives clusterPrimitives) {
    this.group = group;
    this.agentID = agentID;
    this.hz = hz;
    this.clusterPrimitives = clusterPrimitives;
    this.agentGroup = new HazelcastAgentGroup(group, agentID, hz);
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
    Future<Void> remoteDownloadFuture = executeAsync(agentID,
        (RemoteRunnable) () -> downloadFilesFromQueue(instanceId, new RemoteClientManager(instanceId).getClientClasspathRoot()));
    uploadFiles(instanceId, locations, remoteDownloadFuture);
  }

  @Override
  public void uploadKit(AgentID agentID, InstanceId instanceId, Distribution distribution, String kitInstallationName, Path kitInstallationPath) {
    Future<Void> remoteDownloadFuture = executeAsync(agentID, (RemoteRunnable) () -> {
      RemoteKitManager remoteKitManager = new RemoteKitManager(instanceId, distribution, kitInstallationName);
      Path installDir = remoteKitManager.getKitInstallationPath().getParent();
      downloadFilesFromQueue(instanceId, installDir);
    });
    uploadFiles(instanceId, Collections.singletonList(kitInstallationPath), remoteDownloadFuture);
  }

  /**
   * Static version of {@link Executor#downloadFiles} that avoids capturing {@code this}
   * (which is not serializable) in lambdas submitted to Hazelcast's executor service.
   * Hazelcast serializes all jobs via Java serialization even for same-JVM members,
   * so any lambda that references an instance method would fail serialization.
   */
  private static void downloadFilesFromQueue(InstanceId instanceId, Path dest) {
    HazelcastInstance hz = Hazelcast.getAllHazelcastInstances().iterator().next();
    BlockingQueue<FileTransfer> queue = hz.getQueue(instanceId + "@file-transfer-queue");
    try {
      Files.createDirectories(dest);
      while (true) {
        FileTransfer fileTransfer = queue.take();
        if (fileTransfer.isFinished()) {
          break;
        }
        fileTransfer.writeTo(dest);
      }
      FileUtils.setCorrectPermissions(dest);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
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
        getLocalAgentID() :
        agentGroup.findRemoteAgentID(hostname).orElse(null));
  }

  @Override
  public synchronized AgentGroup getGroup() {
    return agentGroup;
  }

  @Override
  public Cluster getCluster() {
    return new Cluster(clusterPrimitives, agentID, null);
  }

  @Override
  public Cluster getCluster(ClientId clientId) {
    return new Cluster(clusterPrimitives, agentID, clientId);
  }

  @Override
  public Future<Void> executeAsync(AgentID agentID, RemoteRunnable job) {
    logger.debug("Executing job on: {}", agentID);
    Member member = agentGroup.findMember(agentID)
        .orElseThrow(() -> new IllegalArgumentException("No agent found matching: " + agentID + " in group " + group));
    Future<Void> future = hz.getExecutorService(EXECUTOR_SERVICE_NAME)
        .submitToMember(new RunnableAdapter(job), member);
    return new HazelcastFutureAdapter<>(agentID, future);
  }

  @Override
  public <R> Future<R> executeAsync(AgentID agentID, RemoteCallable<R> job) {
    logger.debug("Executing job on: {}", agentID);
    Member member = agentGroup.findMember(agentID)
        .orElseThrow(() -> new IllegalArgumentException("No agent found matching: " + agentID + " in group " + group));
    Future<R> future = hz.getExecutorService(EXECUTOR_SERVICE_NAME)
        .submitToMember(job, member);
    return new HazelcastFutureAdapter<>(agentID, future);
  }

  @Override
  public BlockingQueue<FileTransfer> getFileTransferQueue(InstanceId instanceId) {
    return hz.getQueue(instanceId + "@file-transfer-queue");
  }

  @Override
  public Optional<AgentID> startRemoteAgent(String hostname) {
    // Local mode: all remote hostnames are served by the local agent
    agentGroup.joined(getLocalAgentID(), hostname);
    return Optional.empty();
  }

  /**
   * Serializable adapter that wraps a {@link RemoteRunnable} as a {@link Callable}{@code <Void>}
   * so it can be submitted to Hazelcast's {@link IExecutorService}.
   */
  static final class RunnableAdapter implements Callable<Void>, Serializable {
    private static final long serialVersionUID = 1L;

    private final RemoteRunnable delegate;

    RunnableAdapter(RemoteRunnable delegate) {
      this.delegate = delegate;
    }

    @Override
    public Void call() {
      delegate.run();
      return null;
    }
  }
}
