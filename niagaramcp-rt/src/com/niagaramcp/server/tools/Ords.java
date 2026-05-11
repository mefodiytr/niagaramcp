/*
 * Copyright 2026 niagaramcp contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.niagaramcp.server.tools;

import javax.baja.naming.BOrd;
import javax.baja.naming.SlotPath;
import javax.baja.sys.BComponent;
import javax.baja.sys.BObject;
import javax.baja.sys.Sys;

/**
 * Small ord helpers shared by the v0.5+ write tools.
 *
 * <p>Two concerns, both learned the hard way against a real station:
 * <ul>
 *   <li><b>Resolving</b> — a bare {@code slot:/...} body (no host scheme)
 *       passed to {@code BOrd.make(s).get()} resolves against the servlet
 *       thread's <i>implicit</i> base, which on a station handler thread is
 *       the local host — yielding the cryptic
 *       {@code "ord not resolvable: localhost"}. Always resolve write-tool
 *       ords against the running station; absolute ords
 *       ({@code station:|slot:/...}, {@code local:|...}, {@code h:...})
 *       ignore the base, so this is a no-op for them.</li>
 *   <li><b>Emitting</b> — {@link BComponent#getSlotPathOrd()} and
 *       {@link BComponent#getSlotPath()} both stringify to a <i>relative</i>
 *       {@code slot:/...} body. Tools must hand callers a fully-qualified ord
 *       ({@code "station:|slot:/..."}) so the round-trip through a follow-up
 *       tool call works without the caller having to know to prefix it.</li>
 * </ul>
 */
final class Ords {
  private Ords() {}

  /**
   * Resolve {@code ordStr} against the running station. Use this instead of
   * {@code BOrd.make(ordStr).get()} in write tools so that both fully-qualified
   * ords and bare {@code slot:/...} bodies resolve correctly. Throws whatever
   * {@code BOrd.get} throws on an unresolvable ord — callers wrap it in an
   * {@code ERR_ORD_NOT_RESOLVABLE} RpcException.
   */
  static BObject resolve(String ordStr) {
    return BOrd.make(ordStr).get(Sys.getStation());
  }

  /**
   * Fully-qualified, human-readable ord for a mounted station component,
   * e.g. {@code "station:|slot:/Drivers/Foo"}. Returns {@code null} if
   * {@code c} is null or its slot path can't be obtained (unmounted) — callers
   * fall back to a string-concat of the parent ord and child name.
   */
  static String stationOrd(BComponent c) {
    if (c == null) return null;
    try {
      SlotPath sp = c.getSlotPath();
      return (sp == null) ? null : "station:|" + sp.toString();
    } catch (Exception e) {
      return null;
    }
  }
}
