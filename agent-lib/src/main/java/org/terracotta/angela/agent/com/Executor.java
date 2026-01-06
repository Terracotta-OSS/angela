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

import org.apache.ignite.lang.IgniteCallable;
import org.apache.ignite.lang.IgniteRunnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terracotta.angela.common.clientconfig.ClientId;
import org.terracotta.angela.common.cluster.Cluster;
import org.terracotta.angela.common.distribution.Distribution;
import org.terracotta.angela.common.topology.InstanceId;
import org.terracotta.angela.common.util.FileUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.stream.Stream;

/**
 * @author Mathieu Carbou
 */
public interface Executor extends AutoCloseable {
  Logger logger = LoggerFactory.getLogger(Executor.class);

  AgentID getLocalAgentID();

  Optional<AgentID> findAgentID(String hostname);

  /**
   * Returns the new agentId that has been spawned.
   * If an agent already exists for this hostname,
   * empty optional instead.
   *
   * @param hostname hostname where to start an Agent
   * @return AgentID Agent ID
   */
  Optional<AgentID> startRemoteAgent(String hostname);

  AgentGroup getGroup();

  Cluster getCluster();

  Cluster getCluster(ClientId clientId);

  // ignite calls to target remote agents

  Future<Void> executeAsync(AgentID agentID, IgniteRunnable job);

  <R> Future<R> executeAsync(AgentID agentID, IgniteCallable<R> job);

  BlockingQueue<FileTransfer> getFileTransferQueue(InstanceId instanceId);

  @Override
  void close();

  void uploadClientJars(AgentID agentID, InstanceId instanceId, List<Path> locations);

  void uploadKit(AgentID agentID, InstanceId instanceId, Distribution distribution, String kitInstallationName, Path kitInstallationPath);

  Optional<CompletableFuture<Void>> shutdown(AgentID agentID);

  // defaults

  default void execute(AgentID agentID, IgniteRunnable job) {
    try {
      executeAsync(agentID, job).get();
    } catch (InterruptedException | ExecutionException e) {
      throw Exceptions.asRuntime(e);
    }
  }

  default <R> R execute(AgentID agentID, IgniteCallable<R> job) {
    try {
      return executeAsync(agentID, job).get();
    } catch (InterruptedException | ExecutionException e) {
      throw Exceptions.asRuntime(e);
    }
  }

  default AgentExecutor forAgent(AgentID agentID) {
    return new AgentExecutor(this, agentID);
  }

  default AgentID getAgentID(String hostname) throws NoSuchElementException {
    return findAgentID(hostname).orElseThrow(() -> new NoSuchElementException(hostname));
  }

  default void downloadFiles(InstanceId instanceId, Path dest) {
    try {
      BlockingQueue<FileTransfer> queue = getFileTransferQueue(instanceId);
      logger.debug("Downloading files to: {}", dest);
      Files.createDirectories(dest);
      while (true) {
        FileTransfer fileTransfer = queue.take();
        if (fileTransfer.isFinished()) {
          break;
        }
        fileTransfer.writeTo(dest);
        logger.debug("Downloaded: " + fileTransfer);
      }
      FileUtils.setCorrectPermissions(dest);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  default void uploadFiles(InstanceId instanceId, List<Path> locations, Future<Void> remoteDownloadFuture) {
    try {
      BlockingQueue<FileTransfer> queue = getFileTransferQueue(instanceId);
      try {
        for (Path root : locations) {
          if (Files.exists(root)) {
            logger.debug("Uploading files from: {}", root);
            try (Stream<Path> stream = Files.walk(root).filter(Files::isRegularFile)) {
              stream.map(path -> FileTransfer.from(root, path)).forEach(fileTransfer -> {
                try {
                  queue.put(fileTransfer);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  throw new RuntimeException(e);
                }
                logger.debug("Uploaded: {}", fileTransfer);
              });
            } catch (IOException e) {
              throw new UncheckedIOException(e);
            }
          } else {
            logger.debug("Skipping non-existing root: {}", root);
          }
        }
      } finally {
        queue.put(FileTransfer.END); // end of upload marker
        remoteDownloadFuture.get();
      }
    } catch (ExecutionException | InterruptedException e) {
      throw Exceptions.asRuntime(e);
    }
  }
}
