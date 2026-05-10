/*
 * Copyright 2026 niagaramcp contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.niagaramcp.server.tools;

import javax.baja.naming.BOrd;
import javax.baja.sys.BComponent;
import javax.baja.sys.BLink;
import javax.baja.sys.BObject;
import javax.baja.sys.Property;
import javax.baja.user.BUser;
import com.niagaramcp.json.JSONObject;
import com.niagaramcp.server.CallContext;
import com.niagaramcp.server.McpProtocol;
import com.niagaramcp.server.auth.UserContextGateway;

/**
 * Removes a {@link BLink} slot from its sink component, under the
 * calling user's permissions.
 *
 * <h3>Args</h3>
 * <pre>
 * {
 *   "linkOrd":  "station:|slot:/Logic/Setpoint/RoomTempLink"  // required
 * }
 * </pre>
 *
 * <h3>Result</h3>
 * <pre>
 * {
 *   "linkOrd":   "...",
 *   "sourceOrd": "...",
 *   "sourceSlot":"...",
 *   "sinkOrd":   "...",
 *   "sinkSlot":  "...",
 *   "removed":   true
 * }
 * </pre>
 *
 * <h3>Type-narrowing</h3>
 * Refuses with {@code -32602} if the resolved ord is not a BLink —
 * prevents a slip where {@code unlinkSlots} accidentally removes a
 * regular sub-component. Use {@code removeComponent} for that.
 *
 * <h3>annotations</h3>
 * {@code DESTRUCTIVE} — the original wire info is captured in the
 * result for audit / undo-by-hand, but Niagara has no built-in undo.
 *
 * <h3>Errors</h3>
 * <ul>
 *   <li>{@code -32602} missing arg / ord doesn't resolve to a BLink</li>
 *   <li>{@code -32006} ord not resolvable</li>
 *   <li>{@code -32010} permission denied</li>
 *   <li>{@code -32011} user-Context required</li>
 * </ul>
 */
public final class UnlinkSlotsTool implements Tool {

  @Override public String name()                  { return "unlinkSlots"; }
  @Override public String getCategory()           { return "write"; }
  @Override public boolean requiresUserContext()  { return true; }
  @Override public ToolAnnotations annotations()  { return ToolAnnotations.DESTRUCTIVE; }

  @Override public String description() {
    return "Remove a BLink slot under user-Context. Args: {linkOrd}. Refuses if " +
           "the ord doesn't resolve to a BLink (use removeComponent for non-link " +
           "components). DESTRUCTIVE: the original source/sink wire info is " +
           "captured in the result for audit but Niagara has no built-in undo.";
  }

  @Override public String schemaJson() {
    return ToolSchemaHelpers.objectSchema(
        new String[]{"linkOrd"},
        "linkOrd", ToolSchemaHelpers.ordParam(
            "Ord of the BLink slot to remove (e.g. 'station:|slot:/Logic/Setpoint/RoomTempLink')."));
  }

  @Override
  public String call(JSONObject args) throws Exception {
    final String linkOrdStr = requiredString(args, "linkOrd");

    BUser user = CallContext.user();
    if (user == null) {
      throw new McpProtocol.RpcException(McpProtocol.ERR_USER_NOT_FOUND,
          "unlinkSlots requires a user-Context bearer", oneField("tool", name()));
    }

    BObject obj;
    try {
      obj = BOrd.make(linkOrdStr).get();
    } catch (Exception e) {
      throw new McpProtocol.RpcException(McpProtocol.ERR_ORD_NOT_RESOLVABLE,
          "linkOrd not resolvable: " + e.getMessage(), oneField("ord", linkOrdStr));
    }
    if (!(obj instanceof BLink)) {
      throw new McpProtocol.RpcException(McpProtocol.ERR_INVALID_PARAMS,
          "linkOrd does not point to a BLink (use removeComponent for non-link slots)",
          oneField("ord", linkOrdStr));
    }
    final BLink link = (BLink) obj;

    BComponent sink = (BComponent) link.getParent();
    if (sink == null) {
      throw new McpProtocol.RpcException(McpProtocol.ERR_INTERNAL,
          "BLink has no parent component", oneField("ord", linkOrdStr));
    }

    // Capture wire info BEFORE removal (for audit / result envelope).
    BComponent sourceComp = link.getSourceComponent();
    BComponent targetComp = link.getTargetComponent();
    String sourceSlot     = link.getSourceSlotName();
    String targetSlot     = link.getTargetSlotName();

    final Property linkProperty = link.getPropertyInParent();
    if (linkProperty == null) {
      throw new McpProtocol.RpcException(McpProtocol.ERR_INTERNAL,
          "Could not resolve link's slot in its parent", oneField("ord", linkOrdStr));
    }

    UserContextGateway.OpDesc op =
        UserContextGateway.OpDesc.of(name(), linkOrdStr, "unlink");
    String sessionId = CallContext.sessionId();

    UserContextGateway.run(user, op, args, sessionId, cx -> {
      sink.remove(linkProperty, cx);
      return null;
    });

    JSONObject result = new JSONObject();
    result.put("linkOrd",   linkOrdStr);
    result.put("sourceOrd", sourceComp == null ? "" : sourceComp.getSlotPath().toString());
    result.put("sourceSlot", sourceSlot);
    result.put("sinkOrd",   targetComp == null ? "" : targetComp.getSlotPath().toString());
    result.put("sinkSlot",  targetSlot);
    result.put("removed",   true);
    return result.toString();
  }

  private static String requiredString(JSONObject args, String key) {
    String v = args.optString(key, "");
    if (v == null || v.isEmpty()) {
      throw new McpProtocol.RpcException(McpProtocol.ERR_INVALID_PARAMS,
          "Missing required arg: " + key, oneField("missing", key));
    }
    return v;
  }

  private static JSONObject oneField(String k, Object v) {
    JSONObject d = new JSONObject();
    d.put(k, v);
    return d;
  }
}
