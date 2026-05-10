/*
 * Copyright 2026 niagaramcp contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.niagaramcp.server.tools;

import javax.baja.sys.BStation;
import javax.baja.sys.Sys;
import javax.baja.user.BUser;
import com.niagaramcp.json.JSONObject;
import com.niagaramcp.server.CallContext;
import com.niagaramcp.server.McpProtocol;
import com.niagaramcp.server.auth.UserContextGateway;

/**
 * Forces an immediate save of the station's .bog under the calling
 * user's permissions, audited as a write operation.
 *
 * <h3>When to use</h3>
 * Niagara auto-saves the .bog every ~30 seconds. Between mutation and
 * auto-save, a station reboot loses the change. {@code commitStation}
 * gives the client an explicit "make this durable now" hook for
 * scenarios where ack-without-persistence is unacceptable
 * (compliance writes, batch finalizers, before-shutdown sequences).
 *
 * <h3>Args</h3>
 * <pre>
 * {}   // no args
 * </pre>
 *
 * <h3>Result</h3>
 * <pre>
 * {
 *   "saved":      true,
 *   "stationName": "MyStation",
 *   "durationMs": 142
 * }
 * </pre>
 *
 * <h3>Implementation note</h3>
 * Uses {@code BStation.doSave(cx)} via the
 * {@link com.niagaramcp.server.auth.UserContextGateway} so the save
 * runs under the calling user's permissions (PermissionException →
 * {@code -32010} with rich data envelope). The simpler
 * {@code BStation.save()} doesn't take a Context, so it would run
 * under whatever default identity the calling thread has — defeats
 * the audit story.
 *
 * <h3>annotations</h3>
 * {@code MUTATION} (not destructive — saving doesn't lose data) but
 * {@code idempotentHint=false} because each save flushes a different
 * .bog snapshot reflecting whatever's mutated since last save.
 *
 * <h3>Errors</h3>
 * <ul>
 *   <li>{@code -32010} — user lacks station-config-write permission</li>
 *   <li>{@code -32011} — user-Context required (pre-dispatch)</li>
 *   <li>{@code -32603} — save threw (disk full, IO error, …); the
 *       gateway captures the exception message in error.data.detail</li>
 * </ul>
 */
public final class CommitStationTool implements Tool {

  @Override public String name()                  { return "commitStation"; }
  @Override public String getCategory()           { return "write"; }
  @Override public boolean requiresUserContext()  { return true; }
  @Override public ToolAnnotations annotations()  { return ToolAnnotations.MUTATION; }

  @Override public String description() {
    return "Force a synchronous save of the station's .bog under the calling " +
           "user's permissions. Use after a batch of mutations when ack-without-" +
           "persistence (~30s auto-save delay) is unacceptable. Returns when the " +
           "save completes.";
  }

  @Override public String schemaJson() {
    return ToolSchemaHelpers.emptySchema();
  }

  @Override
  public String call(JSONObject args) throws Exception {
    BUser user = CallContext.user();
    if (user == null) {
      throw new McpProtocol.RpcException(McpProtocol.ERR_USER_NOT_FOUND,
          "commitStation requires a user-Context bearer", oneField("tool", name()));
    }

    final BStation station = Sys.getStation();
    if (station == null) {
      throw new McpProtocol.RpcException(McpProtocol.ERR_INTERNAL,
          "Station not available", null);
    }

    UserContextGateway.OpDesc op =
        UserContextGateway.OpDesc.of(name(), station.getSlotPath().toString(), "save");
    String sessionId = CallContext.sessionId();

    final long t0 = System.currentTimeMillis();
    UserContextGateway.run(user, op, args, sessionId, cx -> {
      station.doSave(cx);
      return null;
    });
    final long durationMs = System.currentTimeMillis() - t0;

    JSONObject result = new JSONObject();
    result.put("saved", true);
    result.put("stationName", station.getStationName());
    result.put("durationMs", durationMs);
    return result.toString();
  }

  private static JSONObject oneField(String k, Object v) {
    JSONObject d = new JSONObject();
    d.put(k, v);
    return d;
  }
}
