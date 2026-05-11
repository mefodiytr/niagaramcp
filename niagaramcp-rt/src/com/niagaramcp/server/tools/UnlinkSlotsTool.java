/*
 * Copyright 2026 niagaramcp contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.niagaramcp.server.tools;

import javax.baja.sys.BComponent;
import javax.baja.sys.BLink;
import javax.baja.sys.BObject;
import javax.baja.sys.BValue;
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
 * <h3>Args (one of two forms)</h3>
 * <pre>
 * { "linkOrd": "station:|slot:/Logic/Setpoint/RoomTempLink" }   // direct
 *   — or —
 * { "sinkOrd": "station:|slot:/Logic/Setpoint", "linkName": "RoomTempLink" }
 * </pre>
 * The {@code {sinkOrd, linkName}} form mirrors how {@code linkSlots} returns
 * its result and how links read in the nav tree (a link is a named slot on
 * the sink). Ords may be absolute or relative ({@code "slot:/..."}).
 *
 * <h3>Result</h3>
 * <pre>
 * {
 *   "linkOrd":   "station:|slot:/Logic/Setpoint/RoomTempLink",
 *   "sourceOrd": "...",
 *   "sourceSlot":"...",
 *   "sinkOrd":   "...",
 *   "sinkSlot":  "...",
 *   "removed":   true
 * }
 * </pre>
 *
 * <h3>Type-narrowing</h3>
 * Refuses with {@code -32602} if the resolved slot is not a BLink —
 * prevents a slip where {@code unlinkSlots} accidentally removes a
 * regular sub-component. Use {@code removeComponent} for that.
 *
 * <h3>annotations</h3>
 * {@code DESTRUCTIVE} — the original wire info is captured in the
 * result for audit / undo-by-hand, but Niagara has no built-in undo.
 *
 * <h3>Errors</h3>
 * <ul>
 *   <li>{@code -32602} neither {linkOrd} nor {sinkOrd,linkName} given /
 *       resolved slot isn't a BLink</li>
 *   <li>{@code -32006} ord not resolvable / sinkOrd not a BComponent /
 *       no such slot</li>
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
    return "Remove a BLink slot under user-Context. Args: {linkOrd} OR " +
           "{sinkOrd, linkName}. Refuses if the resolved slot isn't a BLink " +
           "(use removeComponent for non-link components). DESTRUCTIVE: the " +
           "source/sink wire info is captured in the result for audit but " +
           "Niagara has no built-in undo.";
  }

  @Override public String schemaJson() {
    JSONObject linkOrd  = ToolSchemaHelpers.ordParam(
        "Ord of the BLink slot to remove. Mutually exclusive with sinkOrd/linkName.");
    JSONObject sinkOrd  = ToolSchemaHelpers.ordParam(
        "Ord of the sink BComponent that owns the link slot. Use with linkName.");
    JSONObject linkName = ToolSchemaHelpers.stringParam(
        "Name of the link slot on the sink component. Use with sinkOrd.");
    // No 'required' array: validation is "linkOrd XOR (sinkOrd & linkName)",
    // enforced in call() since JSON Schema can't express it cleanly here.
    return ToolSchemaHelpers.objectSchema(
        new String[0],
        "linkOrd", linkOrd, "sinkOrd", sinkOrd, "linkName", linkName);
  }

  @Override
  public String call(JSONObject args) throws Exception {
    BUser user = CallContext.user();
    if (user == null) {
      throw new McpProtocol.RpcException(McpProtocol.ERR_USER_NOT_FOUND,
          "unlinkSlots requires a user-Context bearer", oneField("tool", name()));
    }

    final String linkOrdArg  = args.optString("linkOrd", "");
    final String sinkOrdArg  = args.optString("sinkOrd", "");
    final String linkNameArg = args.optString("linkName", "");

    final BLink link;
    final String howResolved;   // for error context only
    if (!linkOrdArg.isEmpty()) {
      BObject obj;
      try {
        obj = Ords.resolve(linkOrdArg);
      } catch (Exception e) {
        throw new McpProtocol.RpcException(McpProtocol.ERR_ORD_NOT_RESOLVABLE,
            "linkOrd not resolvable: " + e.getMessage(), oneField("linkOrd", linkOrdArg));
      }
      if (!(obj instanceof BLink)) {
        throw new McpProtocol.RpcException(McpProtocol.ERR_INVALID_PARAMS,
            "linkOrd does not point to a BLink (use removeComponent for non-link slots)",
            oneField("linkOrd", linkOrdArg));
      }
      link = (BLink) obj;
      howResolved = linkOrdArg;
    } else if (!sinkOrdArg.isEmpty() && !linkNameArg.isEmpty()) {
      BObject obj;
      try {
        obj = Ords.resolve(sinkOrdArg);
      } catch (Exception e) {
        throw new McpProtocol.RpcException(McpProtocol.ERR_ORD_NOT_RESOLVABLE,
            "sinkOrd not resolvable: " + e.getMessage(), oneField("sinkOrd", sinkOrdArg));
      }
      if (!(obj instanceof BComponent)) {
        throw new McpProtocol.RpcException(McpProtocol.ERR_ORD_NOT_RESOLVABLE,
            "sinkOrd does not point to a BComponent", oneField("sinkOrd", sinkOrdArg));
      }
      BValue slotVal = ((BComponent) obj).get(linkNameArg);
      if (slotVal == null) {
        JSONObject d = new JSONObject();
        d.put("sinkOrd", sinkOrdArg); d.put("linkName", linkNameArg);
        throw new McpProtocol.RpcException(McpProtocol.ERR_ORD_NOT_RESOLVABLE,
            "No slot \"" + linkNameArg + "\" on " + sinkOrdArg, d);
      }
      if (!(slotVal instanceof BLink)) {
        JSONObject d = new JSONObject();
        d.put("sinkOrd", sinkOrdArg); d.put("linkName", linkNameArg);
        d.put("actualType", slotVal.getType().getTypeSpec().toString());
        throw new McpProtocol.RpcException(McpProtocol.ERR_INVALID_PARAMS,
            "Slot \"" + linkNameArg + "\" on " + sinkOrdArg + " is not a BLink", d);
      }
      link = (BLink) slotVal;
      howResolved = sinkOrdArg + " / " + linkNameArg;
    } else {
      throw new McpProtocol.RpcException(McpProtocol.ERR_INVALID_PARAMS,
          "Provide either {linkOrd} or {sinkOrd, linkName}",
          oneField("hint", "linkOrd OR (sinkOrd AND linkName)"));
    }

    BComponent sink = (BComponent) link.getParent();
    if (sink == null) {
      throw new McpProtocol.RpcException(McpProtocol.ERR_INTERNAL,
          "BLink has no parent component", oneField("link", howResolved));
    }
    final Property linkProperty = link.getPropertyInParent();
    if (linkProperty == null) {
      throw new McpProtocol.RpcException(McpProtocol.ERR_INTERNAL,
          "Could not resolve link's slot in its parent", oneField("link", howResolved));
    }

    // Capture wire info BEFORE removal (for audit / result envelope).
    BComponent sourceComp = link.getSourceComponent();
    BComponent targetComp = link.getTargetComponent();
    String sourceSlot     = link.getSourceSlotName();
    String targetSlot     = link.getTargetSlotName();

    // A BLink is a BRelation, not a BComponent — no getSlotPath() — so build
    // its display ord from the sink's ord + the link slot name.
    String sinkOwnerOrd   = Ords.stationOrd(sink);
    String linkOrdDisplay = (sinkOwnerOrd != null)
        ? sinkOwnerOrd + "/" + linkProperty.getName()
        : (!linkOrdArg.isEmpty() ? linkOrdArg : (sinkOrdArg + "/" + linkNameArg));

    UserContextGateway.OpDesc op =
        UserContextGateway.OpDesc.of(name(), linkOrdDisplay, "unlink");
    String sessionId = CallContext.sessionId();

    UserContextGateway.run(user, op, args, sessionId, cx -> {
      sink.remove(linkProperty, cx);
      return null;
    });

    String srcOrd  = Ords.stationOrd(sourceComp);
    String sinkOrd = Ords.stationOrd(targetComp);
    JSONObject result = new JSONObject();
    result.put("linkOrd",   linkOrdDisplay);
    result.put("sourceOrd", srcOrd  == null ? "" : srcOrd);
    result.put("sourceSlot", sourceSlot);
    result.put("sinkOrd",   sinkOrd == null ? "" : sinkOrd);
    result.put("sinkSlot",  targetSlot);
    result.put("removed",   true);
    return result.toString();
  }

  // ---- helpers ----

  private static JSONObject oneField(String k, Object v) {
    JSONObject d = new JSONObject();
    d.put(k, v);
    return d;
  }
}
