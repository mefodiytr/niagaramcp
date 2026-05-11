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
 * Resets a {@link Property} slot on a {@link BComponent} to its declared
 * default value ({@code Property.getDefaultValue()}). Distinct from
 * {@code setSlot}, which writes a caller-supplied value, and from a
 * hypothetical "null-out" — Niagara slots aren't nullable; "clear" means
 * "back to the type/declaration default".
 *
 * <p>Works for any Property slot type (BSimple or complex) — unlike
 * {@code setSlot} there's no JSON value to coerce, so a {@code BStatusValue}
 * or {@code BFacets} slot can be reset too. (Action / Topic slots aren't
 * Property slots, so {@code getProperty} won't find them → {@code -32006}.)
 *
 * <p>Note: for a <em>dynamic</em> property (one added at runtime via
 * {@code add(name, value, cx)}) {@code getDefaultValue()} tracks the
 * property's current value, so {@code clearSlot} is effectively a no-op
 * there ({@code changed=false}). It's meaningful for <em>frozen</em>
 * properties, which carry a declared {@code defaultValue} distinct from
 * whatever the slot currently holds.
 *
 * <h3>Args</h3>
 * <pre>
 * {
 *   "ord":      "station:|slot:/Drivers/Foo",  // required (absolute or
 *                                              //   relative "slot:/...")
 *   "slotName": "displayName"                  // required
 * }
 * </pre>
 *
 * <h3>Result</h3>
 * <pre>
 * {
 *   "ord":            "...",
 *   "slotName":       "displayName",
 *   "previousValue":  "Heating Plant 4F",
 *   "defaultValue":   "",
 *   "type":           "baja:String",
 *   "changed":        true            // false if already at default
 * }
 * </pre>
 *
 * <h3>requiresUserContext + annotations</h3>
 * {@code requiresUserContext=true}, {@code annotations=MUTATION}.
 *
 * <h3>Error codes</h3>
 * <ul>
 *   <li>{@code -32602} missing arg</li>
 *   <li>{@code -32006} ord not resolvable / not a BComponent / no such
 *       Property slot on the component</li>
 *   <li>{@code -32010} permission denied</li>
 *   <li>{@code -32011} user-Context required (pre-dispatch)</li>
 *   <li>{@code -32603} the slot rejected the write (e.g. read-only slot)
 *       — Niagara's exception message is carried through</li>
 * </ul>
 */
public final class ClearSlotTool implements Tool {

  @Override public String name()                  { return "clearSlot"; }
  @Override public String getCategory()           { return "write"; }
  @Override public boolean requiresUserContext()  { return true; }
  @Override public ToolAnnotations annotations()  { return ToolAnnotations.MUTATION; }

  @Override public String description() {
    return "Reset a Property slot on a BComponent to its declared default " +
           "value, under user-Context. Args: {ord, slotName}. Works for any " +
           "Property slot type; use setSlot to write a specific value instead.";
  }

  @Override public String schemaJson() {
    return ToolSchemaHelpers.objectSchema(
        new String[]{"ord", "slotName"},
        "ord",      ToolSchemaHelpers.ordParam(
            "Ord of the BComponent owning the slot."),
        "slotName", ToolSchemaHelpers.stringParam(
            "Name of the Property slot to reset (must exist on the component)."));
  }

  @Override
  public String call(JSONObject args) throws Exception {
    final String ordStr   = requiredString(args, "ord");
    final String slotName = requiredString(args, "slotName");

    BUser user = CallContext.user();
    if (user == null) {
      throw new McpProtocol.RpcException(McpProtocol.ERR_USER_NOT_FOUND,
          "clearSlot requires a user-Context bearer", oneField("tool", name()));
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
          "No such Property slot \"" + slotName + "\" on " + ordStr, d);
    }

    final BValue existing  = target.get(prop);
    final BValue defaultVal = prop.getDefaultValue();
    final boolean changed   = !prop.isEquivalentToDefaultValue(existing);

    UserContextGateway.OpDesc op =
        UserContextGateway.OpDesc.of(name(), ordStr, "clear");
    String sessionId = CallContext.sessionId();

    UserContextGateway.run(user, op, args, sessionId, cx -> {
      target.set(prop, defaultVal, cx);
      return null;
    });

    JSONObject result = new JSONObject();
    result.put("ord", ordStr);
    result.put("slotName", slotName);
    result.put("previousValue", BValueCoercer.toJsonScalar(existing));
    result.put("defaultValue", BValueCoercer.toJsonScalar(defaultVal));
    result.put("type", BValueCoercer.typeSpec(existing));
    result.put("changed", changed);
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
