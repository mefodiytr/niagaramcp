/*
 * Copyright 2026 niagaramcp contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.niagaramcp.server.knowledge;

import com.niagaramcp.json.JSONObject;
import com.niagaramcp.server.yaml.YamlException;
import com.niagaramcp.server.yaml.YamlReader;
import com.niagaramcp.server.yaml.YamlWriter;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

/**
 * Atomic single-writer load/save orchestrator for the knowledge file.
 *
 * <p>Read accepts both YAML and JSON ({@link YamlReader} sniffs the
 * first character). Write defaults to YAML; format is preserved
 * across round-trips for files that started as JSON.
 *
 * <p>Each persist:
 * <ol>
 *   <li>Serialises the in-memory model to text.</li>
 *   <li>Writes to a {@code .tmp.<millis>} sibling.</li>
 *   <li>Renames existing target to a timestamped backup.</li>
 *   <li>Atomically moves tmp into place ({@code ATOMIC_MOVE}).</li>
 *   <li>Appends a JSON line to {@code knowledge.audit.log}.</li>
 *   <li>Prunes backups beyond the configured limit.</li>
 * </ol>
 */
public final class KnowledgeStore {

  private static final Charset UTF8 = Charset.forName("UTF-8");

  /** Format the on-disk file uses; preserved across writes. */
  public enum Format { YAML, JSON }

  private final Object lock = new Object();
  private File file;
  private Format format = Format.YAML;
  private KnowledgeModel model = new KnowledgeModel();
  private boolean autoBackup = true;
  private int backupCount = 5;

  public KnowledgeStore() {}

  // ------------------------------------------------------------------
  // Configuration
  // ------------------------------------------------------------------

  public void setFile(File f)             { synchronized (lock) { this.file = f; } }
  public File getFile()                   { synchronized (lock) { return file; } }
  public void setAutoBackup(boolean b)    { synchronized (lock) { this.autoBackup = b; } }
  public void setBackupCount(int n)       { synchronized (lock) { this.backupCount = (n < 0 ? 0 : n); } }

  /** Read-only view of the current in-memory model. */
  public KnowledgeModel getModel()        { synchronized (lock) { return model; } }

  /** Format the current file uses (or YAML if no file yet loaded). */
  public Format getFormat()               { synchronized (lock) { return format; } }

  // ------------------------------------------------------------------
  // Lifecycle
  // ------------------------------------------------------------------

  /**
   * Load from file (creates empty model if file missing). Idempotent.
   */
  public void load() throws IOException, KnowledgeException {
    synchronized (lock) {
      if (file == null) throw new IllegalStateException("KnowledgeStore: file not set");
      if (!file.exists()) {
        this.model = new KnowledgeModel();
        this.format = Format.YAML;
        return;
      }
      String text = new String(Files.readAllBytes(file.toPath()), UTF8);
      Object tree;
      try {
        tree = YamlReader.parse(text);
      } catch (YamlException e) {
        throw new KnowledgeException("Failed to parse " + file + ": " + e.getMessage(), e);
      }
      // Detect format
      this.format = sniffFormat(text);
      this.model = KnowledgeModel.fromTree(tree);
    }
  }

  /** Force a fresh re-read from disk. Same as {@link #load()}. */
  public void reload() throws IOException, KnowledgeException {
    load();
  }

  /**
   * Persist current model atomically. Action and id strings are recorded
   * in the audit log; sessionId is best-effort (may be null).
   */
  public void save(String action, String id, String sessionId) throws IOException {
    synchronized (lock) {
      if (file == null) throw new IllegalStateException("KnowledgeStore: file not set");
      File parent = file.getParentFile();
      if (parent != null && !parent.exists() && !parent.mkdirs()) {
        throw new IOException("Cannot create knowledge dir: " + parent);
      }
      String text = serialise(model);
      File tmp = new File(parent != null ? parent : new File("."),
                          file.getName() + ".tmp." + System.currentTimeMillis());
      Files.write(tmp.toPath(), text.getBytes(UTF8),
                  StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

      // Backup existing target
      if (autoBackup && file.exists()) {
        File bak = new File(parent != null ? parent : new File("."),
                            file.getName() + ".bak." + isoStamp());
        try {
          Files.move(file.toPath(), bak.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ioe) {
          // backup failure is non-fatal; proceed
        }
        pruneBackups(parent != null ? parent : new File("."), file.getName());
      }

      // Atomic rename tmp -> target
      try {
        Files.move(tmp.toPath(), file.toPath(),
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (IOException ioe) {
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
      }

      writeAuditLog(parent != null ? parent : new File("."), action, id, sessionId);
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

  private void writeAuditLog(File dir, String action, String id, String sessionId)
      throws IOException {
    File audit = new File(dir, "knowledge.audit.log");
    JSONObject e = new JSONObject();
    e.put("ts",        isoStampMs());
    e.put("action",    action == null ? "unknown" : action);
    e.put("id",        id == null ? "" : id);
    e.put("sessionId", sessionId == null ? "" : sessionId);
    String line = e.toString() + "\n";
    Files.write(audit.toPath(), line.getBytes(UTF8),
        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
  }

  private void pruneBackups(File dir, String baseName) {
    final String prefix = baseName + ".bak.";
    File[] all = dir.listFiles();
    if (all == null) return;
    List<File> baks = new ArrayList<File>();
    for (File f : all) if (f.getName().startsWith(prefix)) baks.add(f);
    if (baks.size() <= backupCount) return;
    Collections.sort(baks, new Comparator<File>() {
      public int compare(File a, File b) { return a.getName().compareTo(b.getName()); }
    });
    int toDelete = baks.size() - backupCount;
    for (int i = 0; i < toDelete; i++) {
      File f = baks.get(i);
      if (!f.delete()) f.deleteOnExit();
    }
  }

  private static String isoStamp() {
    SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH-mm-ss");
    fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
    return fmt.format(new Date());
  }

  private static String isoStampMs() {
    SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
    fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
    return fmt.format(new Date());
  }
}
