/*
 * Copyright 2026 niagaramcp contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.niagaramcp.server.audit;

import com.niagaramcp.json.JSONObject;
import com.niagaramcp.server.NiagaraFileUtil;

import java.io.File;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

/**
 * Primary audit sink — appends one JSON line per call to
 * {@code <userHome>/niagaramcp/niagaramcp.audit.log} (path can be
 * overridden in the constructor).
 *
 * <p>Line shape (single line per record, no embedded newlines):
 * <pre>
 * {"ts":"2026-05-10T14:23:45.123Z","user":"alice","sessionId":"…",
 *  "tool":"createComponent","ord":"station:|slot:/Drivers",
 *  "action":"add","args":{...redacted...},"resultOk":true,
 *  "durationMs":12,"errorCode":"","errorMessage":""}
 * </pre>
 *
 * <p>Args are passed through {@link AuditRedactor} before serialization.
 *
 * <p>Synchronous append, single-thread-safe via a {@code synchronized}
 * block on {@link #lock}. No log rotation in v0.5 — operator-side
 * logrotate or equivalent handles it. Rotation by file-size will land
 * in v0.5.x as a small follow-up.
 */
public final class JsonlAuditWriter implements Audit.Auditor {

  private static final Charset UTF8 = Charset.forName("UTF-8");
  /** ISO-8601 UTC with milliseconds. */
  private final SimpleDateFormat iso = mkIsoFormat();

  private final Object lock = new Object();
  private final File file;
  private final AuditRedactor redactor;

  public JsonlAuditWriter(File file) {
    this(file, new AuditRedactor());
  }

  public JsonlAuditWriter(File file, AuditRedactor redactor) {
    this.file = file;
    this.redactor = (redactor == null) ? new AuditRedactor() : redactor;
  }

  @Override
  public void audit(Audit.AuditRecord r) throws Exception {
    if (r == null) return;
    JSONObject line = new JSONObject();
    line.put("ts",            iso.format(new Date(r.timestampMs)));
    line.put("user",          r.user);
    line.put("sessionId",     r.sessionId);
    line.put("tool",          r.tool);
    line.put("ord",           r.ord);
    line.put("action",        r.action);
    line.put("args",          redactor.redact(r.args == null ? new JSONObject() : r.args));
    line.put("resultOk",      r.resultOk);
    line.put("durationMs",    r.durationMs);
    line.put("errorCode",     r.errorCode);
    line.put("errorMessage",  r.errorMessage);

    String s = line.toString() + "\n";
    synchronized (lock) {
      NiagaraFileUtil.append(file, s);
    }
  }

  /** @return the audit file path (for diagnostics / health checks). */
  public File getFile() { return file; }

  /** Permission-safe existence check — avoids raw {@code File.exists()}. */
  public boolean exists() {
    return NiagaraFileUtil.exists(file);
  }

  private static SimpleDateFormat mkIsoFormat() {
    SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
    f.setTimeZone(TimeZone.getTimeZone("UTC"));
    return f;
  }
}
