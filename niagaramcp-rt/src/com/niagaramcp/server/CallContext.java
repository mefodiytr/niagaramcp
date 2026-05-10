/*
 * Copyright 2026 niagaramcp contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.niagaramcp.server;

import javax.baja.user.BUser;

/**
 * Per-request scoped context for an in-flight {@code tools/call}.
 * Carries the resolved {@link BUser} (or {@code null} for service
 * identity) and the MCP session id so a tool body can pass them
 * into {@link com.niagaramcp.server.auth.UserContextGateway#run}
 * without needing the registry to thread the values through every
 * {@link com.niagaramcp.server.tools.Tool#call} signature.
 *
 * <p>Set by {@link McpProtocol#callTool} immediately before invoking
 * the tool, cleared in its {@code finally}. Never visible across
 * requests because Niagara's servlet container processes each
 * request on its own worker thread and we clear inside the same
 * dispatch call.
 *
 * <p>This is the only thread-local in v0.5. The alternative — adding
 * a fourth argument to {@link com.niagaramcp.server.tools.Tool#call}
 * — would touch all 36 existing tools, against the "don't rewrite
 * existing" rule.
 */
public final class CallContext {

  private static final ThreadLocal<Holder> TL = new ThreadLocal<Holder>();

  private CallContext() {}

  public static void set(BUser user, String sessionId) {
    TL.set(new Holder(user, sessionId == null ? "" : sessionId));
  }

  public static void clear() { TL.remove(); }

  /** @return resolved BUser, or {@code null} if outside a tools/call dispatch
   *          or if auth was via the service apiToken. */
  public static BUser user() {
    Holder h = TL.get();
    return h == null ? null : h.user;
  }

  /** @return MCP session id or empty string if outside a tools/call dispatch. */
  public static String sessionId() {
    Holder h = TL.get();
    return h == null ? "" : h.sessionId;
  }

  private static final class Holder {
    final BUser user;
    final String sessionId;
    Holder(BUser user, String sessionId) {
      this.user = user;
      this.sessionId = sessionId;
    }
  }
}
