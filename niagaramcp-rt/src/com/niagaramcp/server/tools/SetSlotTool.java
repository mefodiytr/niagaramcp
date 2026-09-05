/*
 * Copyright 2026 niagaramcp contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.niagaramcp.server.tools;

import javax.baja.sys.BComponent;
import javax.baja.sys.BObject;
import javax.baja.sys.BValue;
import javax.baja.sys.Property;
import javax.baja.user.BUser;
import com.niagaramcp.json.JSONObject;
import com.niagaramcp.server.CallContext;
import com.niagaramcp.server.McpProtocol;
import com.niagaramcp.server.auth.UserContextGateway;

/**
 * Sets a configuration slot on a {@link BComponent} (anything other
 * than priority-slot writable points — those go through
 * {@code writePoint}). Type-coerces the incoming JSON value to the
 * slot's existing BSimple type via {@link BValueCoercer}.
 *
 * <h3>Args</h3>
 * <pre>
 * {
 *   "ord":      "station:|slot:/Drivers/Foo",  // required (absolute or
 *                                              //   relative "slot:/...")
 *   "slotName": "displayName",                 // required
 *   "value":    "Heating Plant 4F"             // required (string /
 *                                              //   number / integer /
 *                                              //   boolean — NOT null)
 * }
 * </pre>
 *
 * <h3>Result</h3>
 * <pre>
 * {
 *   "ord":            "...",
 *   "slotName":       "displayName",
 *   "previousValue":  "...",
 *   "newValue":       "Heating Plant 4F",
 *   "type":           "baja:String"
 * }
 * </pre>
 *
 * <h3>Type coercion</h3>
 * Supported existing slot types: any {@code BSimple}, handled by
 * {@link BValueCoercer}. BString/BBoolean/BInteger/BLong/BFloat/BDouble
 * take JSON scalars; every other {@code BSimple} — BNameMap, frozen enums
 * such as BPollFrequency, BRelTime, BOrd, BAbsTime, BFacets — takes its
 * canonical string form (the same literal {@code getSlots} reports).
 * To reset a slot to its declared default use {@code clearSlot}; to write
 * a status-value priority slot use {@code writePoint}. {@code null} input
 * is refused with {@code -32602} (use {@code clearSlot}). Complex and
 * component slot types are refused with {@code -32602} pointing at the
 * type-specific tool that would be needed. A malformed literal for an
 * otherwise-supported type is also {@code -32602}, and the slot is left
 * unchanged.
 *
 * <h3>requiresUserContext + annotations</h3>
 * {@code requiresUserContext=true}, {@code annotations=MUTATION}.
 *
 * <h3>Error codes</h3>
 * <ul>
 *   <li>{@code -32602} missing arg / unsupported value type / null
 *       value (use {@code clearSlot})</li>
 *   <li>{@code -32006} ord not resolvable / not BComponent / no such
 *       slot on the component</li>
 *   <li>{@code -32010} permission denied</li>
 *   <li>{@code -32011} user-Context required (pre-dispatch)</li>
 * </ul>
 */
public final class SetSlotTool implements Tool {

  @Override public String name()                  { return "setSlot"; }
  @Override public String getCategory()           { return "write"; }
  @Override public boolean requiresUserContext()  { return true; }
  @Override public ToolAnnotations annotations()  { return ToolAnnotations.MUTATION; }

  @Override public String description() {
    return "Set a configuration slot on a BComponent under user-Context. " +
           "Args: {ord, slotName, value}. Coerces value to the slot's existing " +
           "BSimple type (BString/BBoolean/BInteger/BLong/BFloat/BDouble). " +
           "Use clearSlot to reset to default; writePoint for status-value " +
           "priority slots. Complex slot types are not supported.";
  }

  @Override public String schemaJson() {
    return ToolSchemaHelpers.objectSchema(
        new String[]{"ord", "slotName", "value"},
        "ord",      ToolSchemaHelpers.ordParam(
            "Ord of the BComponent owning the slot."),
        "slotName", ToolSchemaHelpers.stringParam(
            "Name of the slot to set (must already exist on the component)."),
        "value",    makeValueParam());
  }

  private static JSONObject makeValueParam() {
    JSONObject p = new JSONObject();
    com.niagaramcp.json.JSONArray types = new com.niagaramcp.json.JSONArray();
    types.put("string"); types.put("number"); types.put("integer"); types.put("boolean");
    p.put("type", types);
    p.put("description",
        "New value (string / number / integer / boolean). Coerced to the slot's existing BSimple type.");
    return p;
  }

  @Override
  public String call(JSONObject args) throws Exception {
    final String ordStr   = requiredString(args, "ord");
    final String slotName = requiredString(args, "slotName");
    if (!args.has("value")) {
      throw new McpProtocol.RpcException(McpProtocol.ERR_INVALID_PARAMS,
          "Missing required arg: value", oneField("missing", "value"));
    }
    if (args.isNull("value")) {
      throw new McpProtocol.RpcException(McpProtocol.ERR_INVALID_PARAMS,
          "Null value not supported by setSlot; use clearSlot to reset a slot to its default",
          oneField("hint", "use clearSlot"));
    }

    BUser user = CallContext.user();
    if (user == null) {
      throw new McpProtocol.RpcException(McpProtocol.ERR_USER_NOT_FOUND,
          "setSlot requires a user-Context bearer", oneField("tool", name()));
    }

    BObject obj;
    try {
      obj = Ords.resolve(ordStr);
    } catch (Exception e) {
      throw new McpProtocol.RpcException(McpProtocol.ERR_ORD_NOT_RESOLVABLE,
          "ord not resolvable: " + e.getMessage(), oneField("ord", ordStr));
    }
    if (!(obj instanceof BComponent)) {
      throw new McpProtocol.RpcException(McpProtocol.ERR_ORD_NOT_RESOLVABLE,
          "ord does not point to a BComponent", oneField("ord", ordStr));
    }
    final BComponent target = (BComponent) obj;

    final Property prop = target.getProperty(slotName);
    if (prop == null) {
      JSONObject d = new JSONObject();
      d.put("ord", ordStr);
      d.put("slotName", slotName);
      throw new McpProtocol.RpcException(McpProtocol.ERR_ORD_NOT_RESOLVABLE,
          "No such slot \"" + slotName + "\" on " + ordStr, d);
    }

    final BValue existing = target.get(prop);
    final Object rawNew = args.get("value");
    final BValue newValue;
    try {
      newValue = BValueCoercer.coerce(existing, rawNew);
    } catch (BValueCoercer.UnsupportedTypeException e) {
      JSONObject d = new JSONObject();
      d.put("ord", ordStr);
      d.put("slotName", slotName);
      d.put("existingType", BValueCoercer.typeSpec(existing));
      d.put("hint", e.hint);
      throw new McpProtocol.RpcException(McpProtocol.ERR_INVALID_PARAMS,
          "Slot type not supported by setSlot", d);
    } catch (NumberFormatException nfe) {
      throw new McpProtocol.RpcException(McpProtocol.ERR_INVALID_PARAMS,
          "Cannot coerce value to existing slot type: " + nfe.getMessage(),
          oneField("value", String.valueOf(rawNew)));
    }

    UserContextGateway.OpDesc op =
        UserContextGateway.OpDesc.of(name(), ordStr, "set");
    String sessionId = CallContext.sessionId();

    UserContextGateway.run(user, op, args, sessionId, cx -> {
      target.set(prop, newValue, cx);
      return null;
    });

    JSONObject result = new JSONObject();
    result.put("ord", ordStr);
    result.put("slotName", slotName);
    result.put("previousValue", BValueCoercer.toJsonScalar(existing));
    result.put("newValue", BValueCoercer.toJsonScalar(newValue));
    result.put("type", BValueCoercer.typeSpec(existing));
    return result.toString();
  }

  // ---- helpers ----

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
