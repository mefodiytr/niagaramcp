/*
 * Copyright 2026 niagaramcp contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.niagaramcp.server;

import javax.baja.file.BFileSystem;
import javax.baja.file.BIFile;
import javax.baja.file.FilePath;
import javax.baja.sys.Context;
import javax.baja.sys.Sys;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * Filesystem access for niagaramcp's own housekeeping data (knowledge file,
 * audit logs) that goes through {@link BFileSystem} instead of raw
 * {@code java.io}/{@code java.nio.file}.
 *
 * <p>Direct OS file I/O from third-party module code is subject to the
 * hosting station's own {@code java.io.FilePermission} grants, which are not
 * available to third-party modules on every Niagara distribution (no
 * {@code FILE_ACCESS}-equivalent {@code NiagaraPermissionGroup} exists to
 * request them — see project issue #1). Routing through {@code BFileSystem}
 * with a null-user {@link Context} uses Niagara's own (permissive for
 * internal/system callers) access checks instead, which is consistent across
 * distributions.
 */
public final class NiagaraFileUtil {

  private static final Charset UTF8 = Charset.forName("UTF-8");

  private NiagaraFileUtil() {}

  /**
   * Converts an absolute {@link File} to the {@link FilePath} needed to go
   * through {@link BFileSystem}.
   *
   * <p>{@code Sys.getNiagaraUserHome()} points at Niagara's own internal
   * {@code tridium} directory (stations, security, registry, ...) — a
   * location {@link BFileSystem#localFileToPath} refuses to map ("Cannot map
   * to path"), since it's intentionally outside the generic file-space
   * abstraction's scope. For anything under that home we instead build a
   * {@code ~}-prefixed (user-home-relative) path directly, which
   * {@link FilePath} always accepts regardless of what BFileSystem considers
   * mappable. Anything else (e.g. an operator-supplied absolute
   * {@code knowledgeFilePath} override) falls back to localFileToPath.
   */
  public static FilePath toFilePath(File f) {
    File userHome = Sys.getNiagaraUserHome();
    String userHomePath = userHome.getAbsolutePath();
    String path = f.getAbsolutePath();
    if (path.equals(userHomePath) || path.startsWith(userHomePath + File.separator)) {
      String rel = path.substring(userHomePath.length()).replace(File.separatorChar, '/');
      while (rel.startsWith("/")) rel = rel.substring(1);
      return new FilePath("~" + rel);
    }
    return BFileSystem.INSTANCE.localFileToPath(f);
  }

  public static boolean exists(File f) {
    if (f == null) return false;
    return BFileSystem.INSTANCE.findFile(toFilePath(f)) != null;
  }

  public static long length(File f) {
    if (f == null) return 0;
    BIFile bf = BFileSystem.INSTANCE.findFile(toFilePath(f));
    return bf != null ? bf.getSize() : 0;
  }

  public static long lastModified(File f) {
    if (f == null) return 0;
    BIFile bf = BFileSystem.INSTANCE.findFile(toFilePath(f));
    return bf != null ? bf.getLastModified().getMillis() : 0;
  }

  /** Whether niagaramcp's internal (null-user) context can read this path. */
  public static boolean canRead(File f) {
    if (f == null) return false;
    try {
      BFileSystem.INSTANCE.checkReadPermission(toFilePath(f), Context.NULL);
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  /** Whether niagaramcp's internal (null-user) context can write this path. */
  public static boolean canWrite(File f) {
    if (f == null) return false;
    try {
      BFileSystem.INSTANCE.checkWritePermission(toFilePath(f), Context.NULL);
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  public static byte[] readBytes(File f) throws IOException {
    BIFile bf = BFileSystem.INSTANCE.findFile(toFilePath(f));
    if (bf == null) throw new IOException("Not found: " + f);
    return bf.read();
  }

  public static List<String> readLines(File f) throws IOException {
    String text = new String(readBytes(f), UTF8);
    List<String> lines = new ArrayList<String>();
    int start = 0;
    for (int i = 0; i < text.length(); i++) {
      if (text.charAt(i) == '\n') {
        String line = text.substring(start, i);
        if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
        lines.add(line);
        start = i + 1;
      }
    }
    if (start < text.length()) lines.add(text.substring(start));
    return lines;
  }

  /** Appends {@code text} to {@code f}, creating it (and parent dirs) if missing. */
  public static void append(File f, String text) throws IOException {
    FilePath path = toFilePath(f);
    BIFile existing = BFileSystem.INSTANCE.findFile(path);
    byte[] prior = existing != null ? existing.read() : new byte[0];
    byte[] add = text.getBytes(UTF8);
    byte[] combined = new byte[prior.length + add.length];
    System.arraycopy(prior, 0, combined, 0, prior.length);
    System.arraycopy(add, 0, combined, prior.length, add.length);
    BFileSystem.INSTANCE.makeFile(path, Context.NULL).write(combined);
  }

  /** Overwrites {@code f} with {@code bytes} via a create-or-replace file handle. */
  public static void write(File f, byte[] bytes) throws IOException {
    BFileSystem.INSTANCE.makeFile(toFilePath(f), Context.NULL).write(bytes);
  }

  public static void move(File from, File to) throws IOException {
    BFileSystem.INSTANCE.move(toFilePath(from), toFilePath(to), Context.NULL);
  }

  public static void delete(File f) throws IOException {
    BFileSystem.INSTANCE.delete(toFilePath(f), Context.NULL);
  }

  public static void delete(BIFile f) throws IOException {
    BFileSystem.INSTANCE.delete(f.getFilePath(), Context.NULL);
  }

  /** Children of a directory (empty array if it doesn't exist). */
  public static BIFile[] listChildren(File dir) {
    BIFile dirFile = BFileSystem.INSTANCE.findFile(toFilePath(dir));
    if (dirFile == null) return new BIFile[0];
    BIFile[] children = BFileSystem.INSTANCE.getChildren(dirFile);
    return children != null ? children : new BIFile[0];
  }
}
