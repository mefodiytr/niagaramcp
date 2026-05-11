/*
 * Copyright 2026 niagaramcp contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.niagaramcp.server.audit;

import com.niagaramcp.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Facade for niagaramcp's per-user audit log.
 *
 * <p>One static {@link Auditor} instance is installed by
 * {@code BMcpPlatformService.serviceStarted()} via
 * {@link #install(Auditor)}. {@link com.niagaramcp.server.auth.UserContextGateway}
 * calls {@link #emit(AuditRecord)} in its finally-block, every
 * write-tool call therefore generates exactly one audit record.
 *
 * <p>The installed auditor is typically a {@link CompositeAuditor}
 * fanning out to a {@link JsonlAuditWriter} (primary, always-on) and a
 * best-effort {@link BAuditHistoryServiceAdapter} (no-op when the
 * Tridium audit history service is absent or unreachable).
 *
 * <p>If no auditor is installed (e.g. service not started), {@link
 * #emit(AuditRecord)} is a no-op — write-tools never block on audit.
 */
public final class Audit {

  private static volatile Auditor INSTALLED = null;

  private Audit() {}

  public static void install(Auditor a) { INSTALLED = a; }
  public static void clear()             { INSTALLED = null; }

  /** No-op if no auditor installed. Never throws — audit failures
   *  must not break write-tool calls. */
  public static void emit(AuditRecord rec) {
    Auditor a = INSTALLED;
    if (a == null || rec == null) return;
    try { a.audit(rec); } catch (Throwable ignored) { /* never propagate */ }
  }

  // ---- types ----

  /** Sink for {@link AuditRecord}s. */
  public interface Auditor {
    void audit(AuditRecord rec) throws Exception;
  }

  /**
   * Immutable per-call audit record. Fields are public for
   * straightforward serialization; the record itself is built once
   * by {@link com.niagaramcp.server.auth.UserContextGateway} and never
   * mutated.
   */
  public static final class AuditRecord {
    public final long      timestampMs;
    public final String    user;
    public final String    sessionId;     // may be empty for non-Streamable calls
    public final String    tool;
    public final String    ord;
    public final String    action;
    public final JSONObject args;         // pre-redaction; serializers must redact
    public final boolean   resultOk;
    public final long      durationMs;
    public final String    errorCode;     // empty when resultOk
    public final String    errorMessage;  // empty when resultOk

    public AuditRecord(long timestampMs, String user, String sessionId,
                       String tool, String ord, String action,
                       JSONObject args, boolean resultOk, long durationMs,
                       String errorCode, String errorMessage) {
      this.timestampMs  = timestampMs;
      this.user         = nullToEmpty(user);
      this.sessionId    = nullToEmpty(sessionId);
      this.tool         = nullToEmpty(tool);
      this.ord          = nullToEmpty(ord);
      this.action       = nullToEmpty(action);
      this.args         = args;
      this.resultOk     = resultOk;
      this.durationMs   = durationMs;
      this.errorCode    = nullToEmpty(errorCode);
      this.errorMessage = nullToEmpty(errorMessage);
    }

    private static String nullToEmpty(String s) { return s == null ? "" : s; }
  }

  /**
   * Fan-out auditor — calls each child in turn, swallowing any
   * per-child exception so one bad sink doesn't disable the others.
   */
  public static final class CompositeAuditor implements Auditor {
    private final List<Auditor> children = new ArrayList<Auditor>();

    public CompositeAuditor add(Auditor a) {
      if (a != null) children.add(a);
      return this;
    }

    @Override
    public void audit(AuditRecord rec) {
      for (int i = 0; i < children.size(); i++) {
        try { children.get(i).audit(rec); } catch (Throwable ignored) { /* per-child isolated */ }
      }
    }
  }
}
