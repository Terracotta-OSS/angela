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
package org.terracotta.angela.agent.com.grid.ignite;

import org.apache.ignite.IgniteException;
import org.apache.ignite.IgniteInterruptedException;
import org.apache.ignite.cluster.ClusterGroupEmptyException;
import org.apache.ignite.lang.IgniteFuture;
import org.apache.ignite.lang.IgniteFutureTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terracotta.angela.agent.com.AgentID;
import org.terracotta.angela.agent.com.RemoteExecutionException;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class IgniteFutureAdapter<V> implements Future<V> {
  private static final Logger logger = LoggerFactory.getLogger(IgniteFutureAdapter.class);

  private final AgentID agentID;
  private final IgniteFuture<V> igniteFuture;

  public IgniteFutureAdapter(AgentID agentID, IgniteFuture<V> igniteFuture) {
    this.agentID = agentID;
    this.igniteFuture = igniteFuture;
  }

  public AgentID getAgentID() {
    return agentID;
  }

  @Override
  public boolean cancel(boolean mayInterruptIfRunning) {
    return igniteFuture.cancel();
  }

  @Override
  public boolean isCancelled() {
    return igniteFuture.isCancelled();
  }

  @Override
  public boolean isDone() {
    return igniteFuture.isDone();
  }

  @Override
  public V get() throws InterruptedException, ExecutionException {
    try {
      return igniteFuture.get();
    } catch (ClusterGroupEmptyException cge) {
      logger.warn("DETECTED POTENTIAL UNEXPECTED FAILURE (OR KILL) OF JVM WITH NODE: {}", agentID);
      throw new IllegalStateException("Agent is gone in an abrupt way and Ignite cannot get any result from it anymore: " + agentID, cge);
    } catch (IgniteInterruptedException iie) {
      throw (InterruptedException) new InterruptedException().initCause(iie);
    } catch (IgniteException ie) {
      RemoteExecutionException ree = lookForRemoteExecutionException(ie);
      if (ree != null) {
        throw new ExecutionException("Job execution failed on agent: " + agentID, ree);
      } else {
        throw new ExecutionException("Job execution failed on agent: " + agentID, ie);
      }
    }
  }

  @Override
  public V get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
    try {
      return igniteFuture.get(timeout, unit);
    } catch (ClusterGroupEmptyException cge) {
      logger.warn("DETECTED POTENTIAL UNEXPECTED FAILURE (OR KILL) OF JVM WITH NODE: {}", agentID);
      throw new IllegalStateException("Agent is gone in an abrupt way and Ignite cannot get any result from it anymore: " + agentID, cge);
    } catch (IgniteInterruptedException iie) {
      throw (InterruptedException) new InterruptedException().initCause(iie);
    } catch (IgniteFutureTimeoutException ifte) {
      throw (TimeoutException) new TimeoutException().initCause(ifte);
    } catch (IgniteException ie) {
      RemoteExecutionException ree = lookForRemoteExecutionException(ie);
      if (ree != null) {
        throw new ExecutionException("Job execution failed on agent: " + agentID, ree);
      } else {
        throw new ExecutionException("Job execution failed on agent: " + agentID, ie);
      }
    }
  }

  private static RemoteExecutionException lookForRemoteExecutionException(Throwable t) {
    if (t instanceof RemoteExecutionException) {
      return (RemoteExecutionException) t;
    } else if (t == null) {
      return null;
    } else {
      return lookForRemoteExecutionException(t.getCause());
    }
  }
}
