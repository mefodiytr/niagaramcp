/*
 * Copyright 2026 niagaramcp contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.niagaramcp.server;

import java.security.AccessController;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;

/**
 * Runs code under the niagaramcp module's own Java Security Manager grants.
 *
 * <h3>Why</h3>
 * Niagara 4 runs with the Java Security Manager enabled. A permission check
 * walks the whole call stack <em>and</em> the access-control context the
 * thread inherited when it was created. Jetty worker threads are spawned on
 * demand, so a worker can inherit a restricted context from whatever code
 * caused the pool to grow. On such a thread even a plain
 * {@code File.exists()} inside {@code station_home} (which every module is
 * granted) fails with {@code AccessControlException: access denied
 * ("java.io.FilePermission" ... "read")} — intermittently, depending on which
 * worker serves the request.
 *
 * <p>{@link AccessController#doPrivileged} cuts the stack walk at this frame
 * and ignores the inherited context, so only the module's own grants (and
 * those of the Tridium frames it calls) apply. It does not grant anything the
 * module was not already granted, and it does not touch Niagara's user
 * permission model (that is enforced via the {@code Context} passed to Baja
 * calls, see {@link com.niagaramcp.server.auth.UserContextGateway}).
 */
public final class Privileged {

  private Privileged() {}

  /** Body that may throw any checked exception. */
  @FunctionalInterface
  public interface Body<T> {
    T run() throws Exception;
  }

  /**
   * Run {@code body} privileged. Exceptions thrown by the body are re-thrown
   * unchanged (unwrapped from {@link PrivilegedActionException}).
   */
  public static <T> T call(final Body<T> body) throws Exception {
    try {
      return AccessController.doPrivileged(new PrivilegedExceptionAction<T>() {
        @Override public T run() throws Exception { return body.run(); }
      });
    } catch (PrivilegedActionException pae) {
      Exception cause = pae.getException();
      throw (cause != null) ? cause : pae;
    }
  }
}
