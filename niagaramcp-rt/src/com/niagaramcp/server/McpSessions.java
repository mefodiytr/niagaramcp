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

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Static registry of active {@link McpSession}s keyed by sessionId
 * (UUIDv4 generated in {@link #create()}). Closing or removing a session
 * is idempotent.
 */
public final class McpSessions {

  private static final ConcurrentHashMap<String, McpSession> SESSIONS = new ConcurrentHashMap<>();

  public static McpSession create() {
    String id = UUID.randomUUID().toString();
    McpSession s = new McpSession(id);
    SESSIONS.put(id, s);
    return s;
  }

  public static McpSession get(String sessionId) {
    return SESSIONS.get(sessionId);
  }

  public static void remove(String sessionId) {
    McpSession s = SESSIONS.remove(sessionId);
    if (s != null) {
      s.close();
    }
  }

  public static void closeAll() {
    for (McpSession s : SESSIONS.values()) {
      s.close();
    }
    SESSIONS.clear();
  }

  public static int activeCount() {
    return SESSIONS.size();
  }
}
