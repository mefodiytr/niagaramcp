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

/**
 * Streamable-HTTP {@link Session}: state-only object carrying just an id,
 * a {@code lastSeenMs} marker for lazy idle eviction, and
 * closed/initialized flags. No outbound queue — Streamable HTTP responses
 * are written directly to the originating POST's HTTP response.
 *
 * <p>Idle eviction is performed lazily by
 * {@link McpSessions#acquireStreamable(String, long)}: each acquire
 * checks {@code (now - lastSeenMs) > idleTimeoutMs} and removes stale
 * sessions at that point. There is no background sweeper thread.
 */
public final class StreamableSession implements Session {

  /** Default idle timeout: 30 minutes. */
  public static final long DEFAULT_IDLE_TIMEOUT_MS = 30L * 60L * 1000L;

  private final String sessionId;
  private volatile long lastSeenMs;
  private volatile boolean initialized = false;
  private volatile boolean closed = false;

  StreamableSession(String sessionId) {
    this.sessionId = sessionId;
    this.lastSeenMs = System.currentTimeMillis();
  }

  @Override
  public String getSessionId() {
    return sessionId;
  }

  @Override
  public boolean isInitialized() {
    return initialized;
  }

  @Override
  public void markInitialized() {
    this.initialized = true;
  }

  @Override
  public boolean isClosed() {
    return closed;
  }

  @Override
  public void close() {
    this.closed = true;
  }

  @Override
  public void touch() {
    this.lastSeenMs = System.currentTimeMillis();
  }

  /** @return milliseconds since the epoch of the last touch. */
  public long getLastSeenMs() {
    return lastSeenMs;
  }

  /**
   * @return {@code true} if this session has not been touched within
   *         {@code idleTimeoutMs} milliseconds.
   */
  public boolean isStale(long idleTimeoutMs) {
    return (System.currentTimeMillis() - lastSeenMs) > idleTimeoutMs;
  }
}
