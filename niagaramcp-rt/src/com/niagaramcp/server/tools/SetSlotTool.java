/*
 * Copyright 2026 niagaramcp contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.niagaramcp.server.tools;

import javax.baja.naming.BOrd;
import javax.baja.sys.BBoolean;
import javax.baja.sys.BComponent;
import javax.baja.sys.BDouble;
import javax.baja.sys.BFloat;
import javax.baja.sys.BInteger;
import javax.baja.sys.BLong;
import javax.baja.sys.BObject;
import javax.baja.sys.BString;
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
 * slot's existing BSimple type.
 *
 * <h3>Args</h3>
 * <pre>
 * {
 *   "ord":      "station:|slot:/Drivers/Foo",  // required
 *   "slotName": "displayName",                 // required
 *   "value":    "Heating Plant 4F"             // required (any JSON
 *                                              // primitive or null)
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
 * Supported existing slot types (BSimple primitives):
 * {@link BBoolean}, {@link BInteger}, {@link BLong},
 * {@link BFloat}, {@link BDouble}, {@link BString}.
 * For {@code null} input the slot stays at its current value (we
 * don't null-out simple types — that's a different operation,
 * deferred to a future {@code clearSlot} tool).
 *
 * <p>Complex slot types ({@code BStatusValue}, {@code BFacets},
 * {@code BOrd}, etc.) are NOT supported in v0.5.1 — refused with
 * {@code -32602} pointing operator at the type-specific tool that
 * would be needed (writePoint for status values, setFacets for
 * facets — neither exists yet beyond writePoint). Documented as a
 * v0.5.x extension point.
 *
 * <h3>requiresUserContext + annotations</h3>
 * {@code requiresUserContext=true}, {@code annotations=MUTATION}.
 * Setting the same slot to the same value IS effectively idempotent
 * but Niagara still fires the change notifications, so we don't
 * promise idempotency to MCP clients.
 *
 * <h3>Error codes</h3>
 * <ul>
 *   <li>{@code -32602} missing arg / unsupported value type / null
 *       value (use future clearSlot)</li>
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
           "Complex slot types (BStatusValue, BFacets, BOrd, ...) are not " +
           "supported in v0.5.1.";
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
          "Null value not supported in v0.5.1; use a clearSlot tool when added",
          oneField("hint", "use clearSlot (not yet available)"));
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
      newValue = coerce(existing, rawNew);
    } catch (UnsupportedTypeException e) {
      JSONObject d = new JSONObject();
      d.put("ord", ordStr);
      d.put("slotName", slotName);
      d.put("existingType", existing.getType().getTypeSpec().toString());
      d.put("hint", e.hint);
      throw new McpProtocol.RpcException(McpProtocol.ERR_INVALID_PARAMS,
          "Slot type not supported by setSlot in v0.5.1", d);
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
    result.put("previousValue", toJsonScalar(existing));
    result.put("newValue", toJsonScalar(newValue));
    result.put("type", existing.getType().getTypeSpec().toString());
    return result.toString();
  }

  // ---- coercion ----

  private static BValue coerce(BValue existing, Object raw) {
    // Handle the BSimple primitives we support.
    if (existing instanceof BString) {
      return BString.make(raw.toString());
    }
    if (existing instanceof BBoolean) {
      if (raw instanceof Boolean) return BBoolean.make(((Boolean) raw).booleanValue());
      String s = raw.toString().trim().toLowerCase();
      if ("true".equals(s) || "1".equals(s))  return BBoolean.TRUE;
      if ("false".equals(s) || "0".equals(s)) return BBoolean.FALSE;
      throw new NumberFormatException("not a boolean: " + raw);
    }
    if (existing instanceof BInteger) {
      return BInteger.make(toInt(raw));
    }
    if (existing instanceof BLong) {
      return BLong.make(toLong(raw));
    }
    if (existing instanceof BFloat) {
      return BFloat.make((float) toDouble(raw));
    }
    if (existing instanceof BDouble) {
      return BDouble.make(toDouble(raw));
    }
    UnsupportedTypeException e = new UnsupportedTypeException();
    e.hint = "Existing slot type is " + existing.getClass().getSimpleName()
           + "; v0.5.1 setSlot only supports BSimple primitives "
           + "(BString/BBoolean/BInteger/BLong/BFloat/BDouble). "
           + "Future setStatusValue / setFacets tools will cover the rest.";
    throw e;
  }

  private static int toInt(Object raw) {
    if (raw instanceof Number) return ((Number) raw).intValue();
    return Integer.parseInt(raw.toString());
  }

  private static long toLong(Object raw) {
    if (raw instanceof Number) return ((Number) raw).longValue();
    return Long.parseLong(raw.toString());
  }

  private static double toDouble(Object raw) {
    if (raw instanceof Number) return ((Number) raw).doubleValue();
    return Double.parseDouble(raw.toString());
  }

  private static Object toJsonScalar(BValue v) {
    if (v instanceof BString)  return ((BString) v).getString();
    if (v instanceof BBoolean) return ((BBoolean) v).getBoolean();
    if (v instanceof BInteger) return ((BInteger) v).getInt();
    if (v instanceof BLong)    return ((BLong) v).getLong();
    if (v instanceof BFloat)   return ((BFloat) v).getFloat();
    if (v instanceof BDouble)  return ((BDouble) v).getDouble();
    return v.toString();
  }

  private static final class UnsupportedTypeException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    String hint;
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
