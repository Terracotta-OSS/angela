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

import com.hazelcast.core.MemberLeftException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terracotta.angela.agent.com.AgentID;
import org.terracotta.angela.agent.com.RemoteExecutionException;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

class HazelcastFutureAdapter<V> implements Future<V> {
  private static final Logger logger = LoggerFactory.getLogger(HazelcastFutureAdapter.class);

  private final AgentID agentID;
  private final Future<V> delegate;

  HazelcastFutureAdapter(AgentID agentID, Future<V> delegate) {
    this.agentID = agentID;
    this.delegate = delegate;
  }

  @Override
  public boolean cancel(boolean mayInterruptIfRunning) {
    return delegate.cancel(mayInterruptIfRunning);
  }

  @Override
  public boolean isCancelled() {
    return delegate.isCancelled();
  }

  @Override
  public boolean isDone() {
    return delegate.isDone();
  }

  @Override
  public V get() throws InterruptedException, ExecutionException {
    try {
      return delegate.get();
    } catch (ExecutionException ee) {
      throw translateExecutionException(ee);
    }
  }

  @Override
  public V get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
    try {
      return delegate.get(timeout, unit);
    } catch (ExecutionException ee) {
      throw translateExecutionException(ee);
    }
  }

  private ExecutionException translateExecutionException(ExecutionException ee) {
    Throwable cause = ee.getCause();
    if (cause instanceof MemberLeftException) {
      logger.warn("DETECTED POTENTIAL UNEXPECTED FAILURE (OR KILL) OF JVM WITH NODE: {}", agentID);
      throw new IllegalStateException("Agent is gone in an abrupt way and Hazelcast cannot get any result from it anymore: " + agentID, cause);
    }
    RemoteExecutionException ree = lookForRemoteExecutionException(cause);
    if (ree != null) {
      return new ExecutionException("Job execution failed on agent: " + agentID, ree);
    }
    return new ExecutionException("Job execution failed on agent: " + agentID, cause);
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
