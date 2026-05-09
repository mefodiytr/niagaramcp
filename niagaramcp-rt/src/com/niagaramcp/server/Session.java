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
 * Transport-agnostic MCP session abstraction. Implemented by
 * {@link SseSession} (legacy SSE+messages transport) and
 * {@link StreamableSession} (Streamable HTTP transport, MCP spec
 * 2025-06-18). The protocol layer ({@link McpProtocol}) only depends on
 * this interface — it has no knowledge of which transport hosts a given
 * session.
 */
public interface Session {

  /** @return the session identifier (UUID v4) used by clients to correlate requests. */
  String getSessionId();

  /** @return {@code true} once the client has sent {@code notifications/initialized}. */
  boolean isInitialized();

  /** Mark this session as initialized. Called from the protocol dispatcher. */
  void markInitialized();

  /** @return {@code true} after {@link #close()} has been invoked. */
  boolean isClosed();

  /** Mark the session closed. Idempotent. Transport-specific cleanup happens here. */
  void close();

  /**
   * Update the last-seen marker for idle eviction. No-op for transports
   * whose lifecycle is bound to a connection (SSE).
   */
  void touch();
}
