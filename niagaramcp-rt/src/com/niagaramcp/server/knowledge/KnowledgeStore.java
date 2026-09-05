/*
 * Copyright 2026 niagaramcp contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.niagaramcp.server.knowledge;

import com.niagaramcp.json.JSONObject;
import com.niagaramcp.server.NiagaraFileUtil;
import com.niagaramcp.server.yaml.YamlException;
import com.niagaramcp.server.yaml.YamlReader;
import com.niagaramcp.server.yaml.YamlWriter;

import javax.baja.sys.Sys;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

/**
 * Load/save orchestrator for the knowledge model.
 *
 * <p>Persistence goes through a pluggable {@link Backend} rather than raw
 * {@code java.io}/{@code java.nio.file} or even Niagara's own
 * {@code BFileSystem}: on some Niagara distributions (confirmed on Johnson
 * Controls' FX Workbench build — see project issue #1) third-party module
 * code cannot reliably perform OS-level file I/O even via BFileSystem, since
 * that abstraction's own internals aren't consistently privilege-wrapped for
 * unprivileged callers. {@link BMcpPlatformServiceBackend} (wired by
 * {@code BMcpPlatformService}) instead stores the serialized model as a
 * component property, persisted the same way any other station config is —
 * a mechanism that doesn't depend on OS file permissions at all.
 */
public final class KnowledgeStore {

  private static final Charset UTF8 = Charset.forName("UTF-8");

  /** Format the on-disk file uses; preserved across writes. */
  public enum Format { YAML, JSON }

  /**
   * Durable storage for the serialized knowledge document. {@link #read()}
   * returns {@code null} or {@code ""} when nothing has been saved yet.
   * {@link #write} must be durable before returning (a crash immediately
   * after a successful {@code write} must not lose the data).
   */
  public interface Backend {
    String read();
    void write(String text) throws IOException;
    /** Human-readable description of where this is stored, for diagnostics. */
    String describeLocation();
  }

  private final Object lock = new Object();
  private Backend backend;
  private Format format = Format.YAML;
  private KnowledgeModel model = new KnowledgeModel();
  private long lastSavedMs = 0;

  public KnowledgeStore() {}

  // ------------------------------------------------------------------
  // Configuration
  // ------------------------------------------------------------------

  public void setBackend(Backend b)  { synchronized (lock) { this.backend = b; } }
  public Backend getBackend()        { synchronized (lock) { return backend; } }

  /** Read-only view of the current in-memory model. */
  public KnowledgeModel getModel()   { synchronized (lock) { return model; } }

  /**
   * Replaces the entire in-memory model. Caller is responsible for calling
   * {@link #save} afterward if the replacement should be persisted (used by
   * the {@code PUT /niagaramcp/knowledge.yaml} HTTP resource).
   */
  public void setModel(KnowledgeModel m) {
    synchronized (lock) { this.model = (m != null) ? m : new KnowledgeModel(); }
  }

  /** Serializes the current in-memory model without touching the backend. */
  public String toText() {
    synchronized (lock) { return serialise(model); }
  }

  /** Format the current file uses (or YAML if no file yet loaded). */
  public Format getFormat()          { synchronized (lock) { return format; } }

  /** Where the backend is storing data, for diagnostics/display. */
  public String describeLocation() {
    synchronized (lock) { return backend != null ? backend.describeLocation() : "(no backend configured)"; }
  }

  /** Whether a knowledge document has ever been saved (non-empty). */
  public boolean exists() {
    synchronized (lock) {
      if (backend == null) return false;
      String text = backend.read();
      return text != null && text.length() > 0;
    }
  }

  /** Size in bytes (UTF-8) of the currently-persisted document, or 0. */
  public long sizeBytes() {
    synchronized (lock) {
      if (backend == null) return 0;
      String text = backend.read();
      return text != null ? text.getBytes(UTF8).length : 0;
    }
  }

  /** Epoch millis of the last successful {@link #save}, or 0 if never saved this run. */
  public long getLastSavedMs() { synchronized (lock) { return lastSavedMs; } }

  // ------------------------------------------------------------------
  // Lifecycle
  // ------------------------------------------------------------------

  /**
   * Load from the backend (creates empty model if nothing stored yet).
   * Idempotent.
   */
  public void load() throws IOException, KnowledgeException {
    synchronized (lock) {
      if (backend == null) throw new IllegalStateException("KnowledgeStore: backend not set");
      String text = backend.read();
      if (text == null || text.length() == 0) {
        this.model = new KnowledgeModel();
        this.format = Format.YAML;
        return;
      }
      Object tree;
      try {
        tree = YamlReader.parse(text);
      } catch (YamlException e) {
        throw new KnowledgeException("Failed to parse knowledge document: " + e.getMessage(), e);
      }
      this.format = sniffFormat(text);
      this.model = KnowledgeModel.fromTree(tree);
    }
  }

  /** Force a fresh re-read from the backend. Same as {@link #load()}. */
  public void reload() throws IOException, KnowledgeException {
    load();
  }

  /**
   * Persist current model. Action and id strings are recorded in the
   * (best-effort) audit log; sessionId is best-effort (may be null).
   */
  public void save(String action, String id, String sessionId) throws IOException {
    synchronized (lock) {
      if (backend == null) throw new IllegalStateException("KnowledgeStore: backend not set");
      String text = serialise(model);
      backend.write(text);
      lastSavedMs = System.currentTimeMillis();

      // Best-effort secondary audit trail — a file write here is not
      // guaranteed to work on every Niagara distribution (see class
      // javadoc); failure must never fail the knowledge mutation itself,
      // which already succeeded via the backend above.
      try {
        writeAuditLog(action, id, sessionId);
      } catch (Throwable ignored) {
        // best-effort only
      }
    }
  }

  // ------------------------------------------------------------------
  // Sample knowledge resource (jar-bundled)
  // ------------------------------------------------------------------

  public static String readSampleResource() throws IOException {
    InputStream in = KnowledgeStore.class.getResourceAsStream("/sample-knowledge.yaml");
    if (in == null) throw new IOException("sample-knowledge.yaml not found in classpath");
    try {
      java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
      byte[] buf = new byte[4096];
      int n;
      while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
      return new String(bo.toByteArray(), UTF8);
    } finally {
      try { in.close(); } catch (IOException ignore) {}
    }
  }

  // ------------------------------------------------------------------
  // Internals
  // ------------------------------------------------------------------

  private String serialise(KnowledgeModel km) {
    Object tree = km.toTree();
    if (format == Format.JSON) {
      return new JSONObject((java.util.Map<?, ?>) tree).toString(2);
    }
    return YamlWriter.write(tree);
  }

  private static Format sniffFormat(String text) {
    int i = 0;
    int len = text.length();
    while (i < len) {
      char c = text.charAt(i);
      if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '﻿') { i++; continue; }
      if (c == '#') { while (i < len && text.charAt(i) != '\n') i++; continue; }
      return (c == '{' || c == '[') ? Format.JSON : Format.YAML;
    }
    return Format.YAML;
  }

  /**
   * Best-effort secondary audit trail, separate from the per-user
   * {@code niagaramcp.audit.log} ({@code JsonlAuditWriter}). Not durable
   * storage — purely diagnostic, and silently skipped (by the {@code save}
   * caller's try/catch) wherever raw file writes aren't permitted.
   */
  private void writeAuditLog(String action, String id, String sessionId) throws IOException {
    File audit = new File(new File(Sys.getNiagaraUserHome(), "niagaramcp"), "knowledge.audit.log");
    JSONObject e = new JSONObject();
    e.put("ts",        isoStampMs());
    e.put("action",    action == null ? "unknown" : action);
    e.put("id",        id == null ? "" : id);
    e.put("sessionId", sessionId == null ? "" : sessionId);
    String line = e.toString() + "\n";
    NiagaraFileUtil.append(audit, line);
  }

  private static String isoStampMs() {
    SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
    fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
    return fmt.format(new Date());
  }
}
