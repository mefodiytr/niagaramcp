/*
 * Copyright 2026 niagaramcp contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.niagaramcp.server.auth;

import javax.baja.security.PermissionException;
import javax.baja.sys.BasicContext;
import javax.baja.sys.Context;
import javax.baja.user.BUser;
import com.niagaramcp.json.JSONObject;
import com.niagaramcp.server.McpProtocol;

/**
 * Single entry point for write-tools that mutate the Niagara station
 * under the calling user's identity instead of the niagaramcp service
 * identity.
 *
 * <h3>Usage pattern</h3>
 * <pre>
 *   BUser user = ...;  // resolved by BearerResolver
 *   OpDesc op = OpDesc.of("createComponent", parentOrd, "add");
 *   Result r = UserContextGateway.run(user, op, cx -&gt; {
 *     // body executes with cx = new BasicContext(user)
 *     Property p = parent.add(name, value, cx);
 *     return new Result(p.getSlot().getName(), parent.getSlot(p).toString());
 *   });
 * </pre>
 *
 * <h3>Why not thread-local Context?</h3>
 * Niagara has no {@code Context.current()} or {@code runAs(BUser, Runnable)}
 * — every mutating Baja call ({@code add}, {@code set}, {@code remove},
 * {@code rename}, {@code invoke}) takes a {@code Context} parameter
 * explicitly. The work lambda receives the cx and threads it through
 * each call site itself; the gateway exists only to (a) build the
 * Context, (b) wrap PermissionException into our RPC code, and
 * (c) host audit emit (added in commit 7).
 *
 * <h3>What this commit does NOT do yet</h3>
 * Audit integration. The intentional gap is documented in the v0.5
 * implementation notes: AuditWriter (commit 7) plugs into
 * {@code finally} of {@link #run(BUser, OpDesc, ContextAwareWork)}
 * without changing the public contract.
 */
public final class UserContextGateway {

  private UserContextGateway() {}

  /**
   * Execute {@code work} under a {@link BasicContext} built from
   * {@code user}. Permission failures from any Baja call inside
   * {@code work} are translated to
   * {@link McpProtocol.RpcException} with code
   * {@link McpProtocol#ERR_PERMISSION_DENIED} and {@code data} carrying
   * {@code {user, ord, operation}} reconstructed from {@code op}
   * (because {@link PermissionException} itself only exposes a String
   * message — see commit 1 javadoc).
   *
   * <p>{@link McpProtocol.RpcException} thrown from inside {@code work}
   * is re-thrown as-is (lets the tool body raise its own typed errors,
   * e.g. -32006 ord-not-resolvable, before any mutation).
   *
   * <p>Any other exception is wrapped as {@link McpProtocol#ERR_INTERNAL}
   * with the same {@code data} envelope, so the caller always sees a
   * structured response.
   */
  public static <T> T run(BUser user, OpDesc op, ContextAwareWork<T> work) {
    if (user == null) throw new IllegalArgumentException("user must not be null");
    if (op == null)   throw new IllegalArgumentException("op must not be null");
    if (work == null) throw new IllegalArgumentException("work must not be null");

    Context cx = new BasicContext(user);
    try {
      return work.run(cx);
    } catch (PermissionException pe) {
      throw new McpProtocol.RpcException(
          McpProtocol.ERR_PERMISSION_DENIED,
          "Permission denied: " + pe.getMessage(),
          buildData(user, op, pe.getMessage()));
    } catch (McpProtocol.RpcException re) {
      throw re;
    } catch (Exception e) {
      throw new McpProtocol.RpcException(
          McpProtocol.ERR_INTERNAL,
          "Internal: " + e.getMessage(),
          buildData(user, op, e.getMessage()));
    }
  }

  private static JSONObject buildData(BUser user, OpDesc op, String detail) {
    JSONObject d = new JSONObject();
    d.put("user", user.getName());
    d.put("ord", op.ord);
    d.put("operation", op.action);
    d.put("tool", op.tool);
    if (detail != null && !detail.isEmpty()) d.put("detail", detail);
    return d;
  }

  /**
   * Immutable description of the operation being performed, captured at
   * the call-site so the gateway can rebuild error envelopes without
   * needing to introspect the (typed-erased) lambda. Pre-resolution
   * fields only — the actual ord that was created/modified is part of
   * the work's return value, not OpDesc.
   */
  public static final class OpDesc {
    public final String tool;
    public final String ord;
    public final String action;

    private OpDesc(String tool, String ord, String action) {
      this.tool = tool;
      this.ord = ord;
      this.action = action;
    }

    public static OpDesc of(String tool, String ord, String action) {
      return new OpDesc(
          tool == null ? "" : tool,
          ord == null ? "" : ord,
          action == null ? "" : action);
    }
  }

  /**
   * Functional interface for the lambda body of a write-tool. Receives
   * the per-user {@link Context} and returns whatever the tool
   * normally returns (typically a JSON-serializable result object).
   * Allowed to throw any {@link Exception}; the gateway maps it to
   * the right {@link McpProtocol.RpcException} code.
   */
  @FunctionalInterface
  public interface ContextAwareWork<T> {
    T run(Context cx) throws Exception;
  }
}
