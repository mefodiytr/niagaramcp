/*
 * Copyright 2026 niagaramcp contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.niagaramcp.server.tools;

import javax.baja.naming.BOrd;
import javax.baja.sys.Action;
import javax.baja.sys.BBoolean;
import javax.baja.sys.BComponent;
import javax.baja.sys.BDouble;
import javax.baja.sys.BFloat;
import javax.baja.sys.BInteger;
import javax.baja.sys.BLong;
import javax.baja.sys.BObject;
import javax.baja.sys.BString;
import javax.baja.sys.BValue;
import javax.baja.user.BUser;
import com.niagaramcp.json.JSONObject;
import com.niagaramcp.server.CallContext;
import com.niagaramcp.server.McpProtocol;
import com.niagaramcp.server.auth.UserContextGateway;

/**
 * Invokes a Niagara {@link Action} on a {@link BComponent} under the
 * calling user's permissions.
 *
 * <h3>Args</h3>
 * <pre>
 * {
 *   "ord":         "station:|slot:/Drivers/Foo",  // required
 *   "actionName":  "ping",                        // required
 *   "args":        null | "value" | 42 | true     // optional;
 *                                                  // null/missing → use action's parameter default
 * }
 * </pre>
 *
 * <h3>Result</h3>
 * <pre>
 * {
 *   "ord":         "...",
 *   "actionName":  "ping",
 *   "returnValue": "..." | 0.0 | true,
 *   "returnType":  "baja:Null" | "baja:Boolean" | ...,
 *   "durationMs":  1
 * }
 * </pre>
 *
 * <h3>Argument coercion</h3>
 * Same shape as {@code setSlot}: only BSimple primitives
 * (BString/BBoolean/BInteger/BLong/BFloat/BDouble) supported as
 * arguments. Actions whose parameter type is one of these (or
 * {@code baja:Null} = no argument) work cleanly. Actions taking
 * complex types ({@code BFacets}, {@code BAbsTime}, structured
 * args) refuse with {@code -32602} pointing operator at v0.5.x
 * extension.
 *
 * <h3>annotations</h3>
 * {@code MUTATION} as a default. Some actions are read-only
 * (e.g. {@code ping}) but most are state-changing or trigger
 * side-effects; we can't tell from the action signature alone.
 * Operator-side annotation taxonomy could be added later via tags
 * on the Action slot, but not in v0.5.1.
 *
 * <h3>Error codes</h3>
 * <ul>
 *   <li>{@code -32602} — missing arg / unsupported parameter type</li>
 *   <li>{@code -32006} — ord not resolvable / not a BComponent</li>
 *   <li>{@code -32010} — permission denied</li>
 *   <li>{@code -32011} — user-Context required</li>
 *   <li>{@code -32014} ERR_ACTION_NOT_FOUND</li>
 *   <li>{@code -32603} — ActionInvokeException raised by Niagara
 *       (action body threw)</li>
 * </ul>
 */
public final class InvokeActionTool implements Tool {

  @Override public String name()                  { return "invokeAction"; }
  @Override public String getCategory()           { return "write"; }
  @Override public boolean requiresUserContext()  { return true; }
  @Override public ToolAnnotations annotations()  { return ToolAnnotations.MUTATION; }

  @Override public String description() {
    return "Invoke an Action on a BComponent under user-Context. Args: " +
           "{ord, actionName, args?}. args is coerced to the action's " +
           "BSimple parameter type (string/bool/int/long/float/double); " +
           "complex parameter types not supported in v0.5.1. " +
           "Returns the action's return value, type, and execution duration.";
  }

  @Override public String schemaJson() {
    return ToolSchemaHelpers.objectSchema(
        new String[]{"ord", "actionName"},
        "ord",        ToolSchemaHelpers.ordParam(
            "Ord of the component owning the Action."),
        "actionName", ToolSchemaHelpers.stringParam(
            "Action name as declared in the BComponent."),
        "args",       makeArgsParam());
  }

  private static JSONObject makeArgsParam() {
    JSONObject p = new JSONObject();
    com.niagaramcp.json.JSONArray types = new com.niagaramcp.json.JSONArray();
    types.put("string"); types.put("number"); types.put("integer");
    types.put("boolean"); types.put("null");
    p.put("type", types);
    p.put("description",
        "Action argument (BSimple primitive). Omit / null → action's parameter default.");
    return p;
  }

  @Override
  public String call(JSONObject args) throws Exception {
    final String ordStr = requiredString(args, "ord");
    final String actionName = requiredString(args, "actionName");

    BUser user = CallContext.user();
    if (user == null) {
      throw new McpProtocol.RpcException(McpProtocol.ERR_USER_NOT_FOUND,
          "invokeAction requires a user-Context bearer", oneField("tool", name()));
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

    final Action action = target.getAction(actionName);
    if (action == null) {
      JSONObject d = new JSONObject();
      d.put("ord", ordStr);
      d.put("actionName", actionName);
      throw new McpProtocol.RpcException(McpProtocol.ERR_ACTION_NOT_FOUND,
          "No action \"" + actionName + "\" on " + ordStr, d);
    }

    final BValue paramValue;
    if (!args.has("args") || args.isNull("args")) {
      paramValue = action.getParameterDefault();
    } else {
      Object raw = args.get("args");
      try {
        paramValue = coerceForActionParam(action, raw);
      } catch (UnsupportedActionParamException e) {
        JSONObject d = new JSONObject();
        d.put("ord", ordStr);
        d.put("actionName", actionName);
        d.put("paramType", action.getParameterType().getTypeSpec().toString());
        d.put("hint", e.hint);
        throw new McpProtocol.RpcException(McpProtocol.ERR_INVALID_PARAMS,
            "Action parameter type not supported by v0.5.1", d);
      } catch (NumberFormatException nfe) {
        throw new McpProtocol.RpcException(McpProtocol.ERR_INVALID_PARAMS,
            "Cannot coerce args to action parameter type: " + nfe.getMessage(),
            oneField("args", String.valueOf(raw)));
      }
    }

    UserContextGateway.OpDesc op =
        UserContextGateway.OpDesc.of(name(), ordStr, "invoke");
    String sessionId = CallContext.sessionId();

    final long t0 = System.currentTimeMillis();
    BValue ret = UserContextGateway.run(user, op, args, sessionId, cx -> {
      return target.invoke(action, paramValue, cx);
    });
    final long durationMs = System.currentTimeMillis() - t0;

    JSONObject result = new JSONObject();
    result.put("ord", ordStr);
    result.put("actionName", actionName);
    result.put("returnValue", toJsonScalar(ret));
    result.put("returnType",
        ret == null ? "null" : ret.getType().getTypeSpec().toString());
    result.put("durationMs", durationMs);
    return result.toString();
  }

  // ---- coercion ----

  private static BValue coerceForActionParam(Action a, Object raw) {
    BValue defaultVal = a.getParameterDefault();
    if (defaultVal instanceof BString)  return BString.make(raw.toString());
    if (defaultVal instanceof BBoolean) {
      if (raw instanceof Boolean) return BBoolean.make(((Boolean) raw).booleanValue());
      String s = raw.toString().trim().toLowerCase();
      if ("true".equals(s) || "1".equals(s))  return BBoolean.TRUE;
      if ("false".equals(s) || "0".equals(s)) return BBoolean.FALSE;
      throw new NumberFormatException("not a boolean: " + raw);
    }
    if (defaultVal instanceof BInteger) return BInteger.make(toInt(raw));
    if (defaultVal instanceof BLong)    return BLong.make(toLong(raw));
    if (defaultVal instanceof BFloat)   return BFloat.make((float) toDouble(raw));
    if (defaultVal instanceof BDouble)  return BDouble.make(toDouble(raw));
    UnsupportedActionParamException e = new UnsupportedActionParamException();
    e.hint = "Parameter default class is " + (defaultVal == null ? "null" : defaultVal.getClass().getSimpleName())
           + "; v0.5.1 only supports BSimple primitives for action args.";
    throw e;
  }

  private static int    toInt(Object raw)    { return raw instanceof Number ? ((Number) raw).intValue()    : Integer.parseInt(raw.toString()); }
  private static long   toLong(Object raw)   { return raw instanceof Number ? ((Number) raw).longValue()   : Long.parseLong(raw.toString()); }
  private static double toDouble(Object raw) { return raw instanceof Number ? ((Number) raw).doubleValue() : Double.parseDouble(raw.toString()); }

  private static Object toJsonScalar(BValue v) {
    if (v == null)              return null;
    if (v instanceof BString)   return ((BString) v).getString();
    if (v instanceof BBoolean)  return ((BBoolean) v).getBoolean();
    if (v instanceof BInteger)  return ((BInteger) v).getInt();
    if (v instanceof BLong)     return ((BLong) v).getLong();
    if (v instanceof BFloat)    return ((BFloat) v).getFloat();
    if (v instanceof BDouble)   return ((BDouble) v).getDouble();
    return v.toString();
  }

  private static final class UnsupportedActionParamException extends RuntimeException {
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
