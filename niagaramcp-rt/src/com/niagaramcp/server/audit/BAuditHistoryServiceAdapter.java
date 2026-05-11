/*
 * Copyright 2026 niagaramcp contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.niagaramcp.server.audit;

import javax.baja.security.AuditEvent;
import javax.baja.sys.Sys;
import javax.baja.sys.Type;

import java.lang.reflect.Method;

/**
 * Best-effort secondary audit sink that forwards records into
 * {@code com.tridium.history.audit.BAuditHistoryService} (a Tridium-
 * internal service that, when present, surfaces our entries in the
 * Workbench AuditView for compliance / operator visibility).
 *
 * <h3>Why reflection-only</h3>
 * The class is in {@code com.tridium.*}, not {@code javax.baja.*}.
 * <ul>
 *   <li>It's not part of Niagara's public API contract — Tridium can
 *       rename / move it in 4.16+ without depreciation.</li>
 *   <li>{@code history-rt} is not present on every station — lightweight
 *       JACE installs ship without it. A direct compile-time
 *       {@code api(":history-rt")} would put a hard reference into our
 *       bytecode, and verifying the class on those edge stations would
 *       fail with {@code NoClassDefFoundError} at module-load time.
 *       The whole niagaramcp module would refuse to start.</li>
 * </ul>
 * Reflection isolates that ugliness here. {@link Audit.CompositeAuditor}
 * upstream sees only an {@link Audit.Auditor} interface.
 *
 * <h3>Lifecycle</h3>
 * Lookup happens once at {@link #install()} time (called from
 * {@code BMcpPlatformService.serviceStarted()}). The audit method
 * handle is cached for the life of the service. If the lookup fails
 * (class absent, service not running, method moved), {@link #install()}
 * returns an instance whose {@link #audit(Audit.AuditRecord)} is a
 * no-op — exactly one warning is logged via the supplied logger.
 *
 * <h3>Field mapping AuditRecord → AuditEvent</h3>
 * AuditEvent has a fixed 6-field shape:
 * {@code (operation, target, slotName, oldValue, value, userName)}.
 * niagaramcp-style records are richer (args, durationMs, resultOk,
 * sessionId, errorCode/Message). We map:
 * <pre>
 *   operation = action  (e.g. "add", "set", "invoke")
 *   target    = ord
 *   slotName  = tool    (so Workbench AuditView search "by slotName"
 *                        finds all calls to a given tool)
 *   oldValue  = ""
 *   value     = resultOk ? "ok" : "FAIL: " + errorCode
 *   userName  = user
 * </pre>
 * The full record (args, duration, etc.) lives in JSONL only.
 */
public final class BAuditHistoryServiceAdapter implements Audit.Auditor {

  /** Single warning we emit on first lookup failure. */
  public interface WarningSink { void warn(String msg); }

  private final Object service;   // null when reflection lookup failed
  private final Method auditMethod;

  private BAuditHistoryServiceAdapter(Object service, Method auditMethod) {
    this.service = service;
    this.auditMethod = auditMethod;
  }

  /**
   * Resolve the Tridium audit history service via reflection and
   * cache the {@code audit(AuditEvent)} method handle. Always returns
   * a non-null adapter; when the service is unavailable, {@link
   * #audit(Audit.AuditRecord)} no-ops.
   *
   * @param onUnavailable invoked exactly once with a human-readable
   *                      reason when the service can't be found
   *                      (so the caller can log via its preferred
   *                      logger). May be {@code null} to silence.
   */
  public static BAuditHistoryServiceAdapter install(WarningSink onUnavailable) {
    String reason = null;
    Object svc = null;
    Method m = null;
    try {
      Class<?> cls = Class.forName("com.tridium.history.audit.BAuditHistoryService");
      java.lang.reflect.Field typeField = cls.getField("TYPE");
      Type type = (Type) typeField.get(null);
      svc = Sys.getService(type);
      if (svc == null) {
        reason = "BAuditHistoryService class loaded but service not running";
      } else {
        // Inherited from javax.baja.security.Auditor: void audit(AuditEvent)
        m = cls.getMethod("audit", AuditEvent.class);
      }
    } catch (ClassNotFoundException e) {
      reason = "history-rt module not on this station (lightweight JACE?)";
    } catch (Exception e) {
      reason = "reflection lookup failed: " + e.getClass().getSimpleName()
             + ": " + e.getMessage();
    }
    if (reason != null && onUnavailable != null) onUnavailable.warn(reason);
    return new BAuditHistoryServiceAdapter(svc, m);
  }

  @Override
  public void audit(Audit.AuditRecord r) {
    if (service == null || auditMethod == null || r == null) return;
    try {
      AuditEvent ev = new AuditEvent(
          emptyToDash(r.action),                     // operation
          emptyToDash(r.ord),                        // target
          emptyToDash(r.tool),                       // slotName ← tool
          "",                                        // oldValue
          r.resultOk ? "ok" : ("FAIL: " + r.errorCode), // value
          emptyToDash(r.user)                        // userName
      );
      auditMethod.invoke(service, ev);
    } catch (Throwable ignored) {
      // Adapter never propagates — JSONL remains source of truth.
    }
  }

  /** @return {@code true} if the underlying service was successfully bound. */
  public boolean isActive() { return service != null && auditMethod != null; }

  private static String emptyToDash(String s) {
    return (s == null || s.isEmpty()) ? "-" : s;
  }
}
