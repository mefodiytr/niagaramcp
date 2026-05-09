/*
 * Copyright 2026 niagaramcp contributors.
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
package com.niagaramcp.server;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * One SSE client session: holds an outbound JSON-RPC message queue
 * (bounded to {@link #MAX_QUEUE} messages) and a closed/initialized flag.
 * Messages enqueued after overflow are dropped with a warning logged via
 * {@code BMcpPlatformService.bcLog}.
 */
public final class McpSession {

  static final String SENTINEL_CLOSE = "__CLOSE__";
  static final int MAX_QUEUE = 1000;

  private final String sessionId;
  private final BlockingQueue<String> outgoing = new LinkedBlockingQueue<String>(MAX_QUEUE);
  private volatile boolean closed = false;
  private volatile boolean initialized = false;

  public McpSession(String sessionId) {
    this.sessionId = sessionId;
  }

  public String getSessionId() {
    return sessionId;
  }

  public boolean isInitialized() {
    return initialized;
  }

  public void markInitialized() {
    initialized = true;
  }

  public boolean isClosed() {
    return closed;
  }

  public void enqueue(String jsonRpcMessage) {
    if (closed) return;
    if (!outgoing.offer(jsonRpcMessage)) {
      BMcpPlatformService instance = BMcpPlatformService.instance();
      if (instance != null) {
        instance.bcLog("SSE queue overflow (>" + MAX_QUEUE + ") sid=" + sessionId
            + " — dropping message; client likely slow or stuck");
      }
    }
  }

  public String take() throws InterruptedException {
    return outgoing.take();
  }

  public String poll(long timeoutMs) throws InterruptedException {
    return outgoing.poll(timeoutMs, TimeUnit.MILLISECONDS);
  }

  public void close() {
    if (!closed) {
      closed = true;
      outgoing.offer(SENTINEL_CLOSE);
    }
  }
}
