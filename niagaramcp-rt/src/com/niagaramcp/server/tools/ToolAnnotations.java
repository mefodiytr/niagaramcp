/*
 * Copyright 2026 niagaramcp contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.niagaramcp.server.tools;

import com.niagaramcp.json.JSONObject;

/**
 * Behaviour hints for an MCP tool, surfaced in {@code tools/list}
 * response per MCP spec 2025-06-18 §6.1 ("Tool Annotations").
 *
 * <p>MCP-aware clients (Claude Desktop, Cursor, MCP Inspector) read
 * these to gate user-visible warnings — e.g. asking for explicit
 * confirmation before invoking a {@code destructive} tool, or freely
 * batching {@code idempotent} reads. Setting them honestly is a
 * free UX improvement; setting them wrongly (e.g. claiming a write is
 * read-only) breaks client safety.
 *
 * <p>Field meanings (all booleans):
 * <ul>
 *   <li>{@code readOnly}      — does NOT modify the environment
 *   <li>{@code destructive}   — may perform irreversible changes
 *                               (delete, overwrite). Implies
 *                               {@code !readOnly && !idempotent}.
 *   <li>{@code idempotent}    — calling N times has same effect as 1
 *   <li>{@code openWorldHint} — interacts with external systems whose
 *                               state may change independently
 *                               (HTTP fetch, remote DB, etc.). For
 *                               niagaramcp tools this is typically
 *                               {@code false} — station state changes
 *                               via tools we own, not via outside
 *                               actors.
 * </ul>
 *
 * <p>Three presets cover almost all niagaramcp tools:
 * <ul>
 *   <li>{@link #READ_ONLY}    — every read/diagnostic tool (33 of 36
 *                               in v0.4.1).
 *   <li>{@link #MUTATION}     — non-destructive write
 *                               (createComponent, setSlot, addExtension).
 *   <li>{@link #DESTRUCTIVE}  — removeComponent, removeExtension,
 *                               anything irreversible.
 * </ul>
 * Tools that need a non-preset combination construct one with
 * {@link #ToolAnnotations(boolean, boolean, boolean, boolean)}.
 */
public final class ToolAnnotations {

  public final boolean readOnly;
  public final boolean destructive;
  public final boolean idempotent;
  public final boolean openWorldHint;

  public ToolAnnotations(boolean readOnly, boolean destructive,
                         boolean idempotent, boolean openWorldHint) {
    this.readOnly       = readOnly;
    this.destructive    = destructive;
    this.idempotent     = idempotent;
    this.openWorldHint  = openWorldHint;
  }

  /** Pure read / diagnostic — never mutates anything. */
  public static final ToolAnnotations READ_ONLY =
      new ToolAnnotations(true, false, true, false);

  /** Non-destructive write — creates / updates, but no delete or overwrite of irreversible state. */
  public static final ToolAnnotations MUTATION =
      new ToolAnnotations(false, false, false, false);

  /** Destructive — delete / remove / overwrite. Clients should warn the user. */
  public static final ToolAnnotations DESTRUCTIVE =
      new ToolAnnotations(false, true, false, false);

  /** @return the MCP {@code annotations} object for {@code tools/list}. */
  public JSONObject toJson() {
    JSONObject o = new JSONObject();
    o.put("readOnlyHint",      readOnly);
    o.put("destructiveHint",   destructive);
    o.put("idempotentHint",    idempotent);
    o.put("openWorldHint",     openWorldHint);
    return o;
  }
}
