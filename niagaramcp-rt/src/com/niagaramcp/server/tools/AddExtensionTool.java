/*
 * Copyright 2026 niagaramcp contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.niagaramcp.server.tools;

import javax.baja.naming.BOrd;
import javax.baja.sys.BComponent;
import javax.baja.sys.BObject;
import javax.baja.sys.BValue;
import javax.baja.sys.Property;
import javax.baja.sys.Sys;
import javax.baja.sys.Type;
import javax.baja.user.BUser;
import com.niagaramcp.json.JSONObject;
import com.niagaramcp.server.CallContext;
import com.niagaramcp.server.McpProtocol;
import com.niagaramcp.server.auth.UserContextGateway;

/**
 * Adds an extension instance (history ext, alarm ext, proxy ext, ...)
 * as a new child slot under an existing {@link BComponent}, under
 * the calling user's permissions.
 *
 * <h3>Mechanically same as createComponent</h3>
 * The Baja API for adding an extension is the same {@code parent.add(
 * name, value, cx)} that {@code createComponent} uses — extensions
 * are just BComponents whose Type semantically represents an
 * extension-point. We expose them as a separate tool because the
 * UX intent is different (annotating an existing point with history
 * vs. creating a fresh logical component) and so MCP-aware clients
 * can disambiguate.
 *
 * <h3>Pre-check limitations</h3>
 * v0.5.1 does NOT pre-check whether the target type is applicable
 * under the requested parent (e.g. a BHistoryExt under a BFolder
 * usually doesn't make sense). Niagara surfaces incompatibility at
 * {@code add()} time as an exception, which the gateway maps to
 * {@code -32603 ERR_INTERNAL} with the underlying message. A
 * dedicated {@code -32015 ERR_EXTENSION_NOT_APPLICABLE} mapping
 * requires a stable, public predicate API for type-applicability
 * — TBD; future commits or v0.5.2 may pre-check via tags or the
 * {@code BTypeSpec.canHaveChildOf} family if that API is exposed.
 *
 * <h3>Args</h3>
 * <pre>
 * {
 *   "parentOrd":      "station:|slot:/Drivers/MyPoint",  // required
 *   "extensionType":  "history:NumericInterval",         // required
 *   "name":           "Hist",                            // required
 *   "nameStrategy":   "fail" | "suffix"                  // optional, default "fail"
 * }
 * </pre>
 *
 * <h3>Result</h3>
 * <pre>
 * {
 *   "extensionOrd":  "station:|slot:/Drivers/MyPoint/Hist",
 *   "displayName":   "Hist",
 *   "requestedName": "Hist",
 *   "resolvedName":  "Hist"
 * }
 * </pre>
 *
 * <h3>Errors</h3>
 * <ul>
 *   <li>{@code -32602} missing arg / unknown nameStrategy</li>
 *   <li>{@code -32006} parentOrd not resolvable / not BComponent</li>
 *   <li>{@code -32005} extensionType doesn't load / abstract</li>
 *   <li>{@code -32010} permission denied</li>
 *   <li>{@code -32011} user-Context required</li>
 * </ul>
 */
public final class AddExtensionTool implements Tool {

  @Override public String name()                  { return "addExtension"; }
  @Override public String getCategory()           { return "write"; }
  @Override public boolean requiresUserContext()  { return true; }
  @Override public ToolAnnotations annotations()  { return ToolAnnotations.MUTATION; }

  @Override public String description() {
    return "Add an extension instance (history/alarm/proxy ext, ...) as a child of " +
           "an existing component under user-Context. Args: {parentOrd, extensionType, " +
           "name, nameStrategy?}. Niagara enforces type-applicability at add() time; " +
           "incompatible parent/extension pairs surface as -32603 with the underlying " +
           "message until v0.5.2 adds a pre-check.";
  }

  @Override public String schemaJson() {
    return ToolSchemaHelpers.objectSchema(
        new String[]{"parentOrd", "extensionType", "name"},
        "parentOrd",     ToolSchemaHelpers.ordParam(
            "Ord of the BComponent to extend (e.g. a control point)."),
        "extensionType", ToolSchemaHelpers.stringParam(
            "Type spec 'module:TypeName' (e.g. 'history:NumericIntervalExt')."),
        "name",          ToolSchemaHelpers.stringParam(
            "Slot name for the new extension."),
        "nameStrategy",  CreateComponentTool_makeNameStrategyParamShim());
  }

  // Re-uses the same nameStrategy schema as CreateComponentTool —
  // duplicated inline to avoid widening that tool's package surface.
  private static JSONObject CreateComponentTool_makeNameStrategyParamShim() {
    JSONObject p = new JSONObject();
    p.put("type", "string");
    com.niagaramcp.json.JSONArray en = new com.niagaramcp.json.JSONArray();
    en.put("fail"); en.put("suffix");
    p.put("enum", en);
    p.put("description",
        "Behaviour on name collision: \"fail\" (default) → -32602; \"suffix\" → _2/_3/...");
    return p;
  }

  @Override
  public String call(JSONObject args) throws Exception {
    final String parentOrd  = requiredString(args, "parentOrd");
    final String typeSpec   = requiredString(args, "extensionType");
    final String name       = requiredString(args, "name");
    final String strategy   = args.optString("nameStrategy", "fail");

    if (!"fail".equals(strategy) && !"suffix".equals(strategy)) {
      throw new McpProtocol.RpcException(McpProtocol.ERR_INVALID_PARAMS,
          "nameStrategy must be \"fail\" or \"suffix\"",
          oneField("nameStrategy", strategy));
    }

    BUser user = CallContext.user();
    if (user == null) {
      throw new McpProtocol.RpcException(McpProtocol.ERR_USER_NOT_FOUND,
          "addExtension requires a user-Context bearer", oneField("tool", name()));
    }

    BObject parentObj;
    try {
      parentObj = Ords.resolve(parentOrd);
    } catch (Exception e) {
      throw new McpProtocol.RpcException(McpProtocol.ERR_ORD_NOT_RESOLVABLE,
          "parentOrd not resolvable: " + e.getMessage(),
          oneField("ord", parentOrd));
    }
    if (!(parentObj instanceof BComponent)) {
      throw new McpProtocol.RpcException(McpProtocol.ERR_ORD_NOT_RESOLVABLE,
          "parentOrd does not point to a BComponent",
          oneField("ord", parentOrd));
    }
    final BComponent parent = (BComponent) parentObj;

    Type type;
    try {
      type = Sys.getType(typeSpec);
    } catch (Exception e) {
      throw new McpProtocol.RpcException(McpProtocol.ERR_SCHEMA_VALIDATION,
          "Cannot load extensionType: " + e.getMessage(),
          oneField("extensionType", typeSpec));
    }
    BObject inst = type.getInstance();
    if (!(inst instanceof BValue)) {
      throw new McpProtocol.RpcException(McpProtocol.ERR_SCHEMA_VALIDATION,
          "Type " + typeSpec + " does not produce a BValue (abstract type?)",
          oneField("extensionType", typeSpec));
    }
    final BValue value = (BValue) inst;

    String resolvedName = name;
    if (parent.get(name) != null) {
      if ("fail".equals(strategy)) {
        JSONObject d = new JSONObject();
        d.put("parentOrd", parentOrd);
        d.put("name", name);
        throw new McpProtocol.RpcException(McpProtocol.ERR_INVALID_PARAMS,
            "Slot \"" + name + "\" already exists on parent (nameStrategy=fail)", d);
      }
      int n = 2;
      while (parent.get(name + "_" + n) != null) n++;
      resolvedName = name + "_" + n;
    }
    final String resolvedNameFinal = resolvedName;

    UserContextGateway.OpDesc op =
        UserContextGateway.OpDesc.of(name(), parentOrd, "addExtension");
    String sessionId = CallContext.sessionId();

    Property added = UserContextGateway.run(user, op, args, sessionId, cx -> {
      return parent.add(resolvedNameFinal, value, cx);
    });

    // Fully-qualified ord (e.g. "station:|slot:/Drivers/MyPoint/Hist") so a
    // follow-up tool call can resolve it without prefixing it itself.
    BValue mounted = parent.get(added.getName());
    String childOrd = (mounted instanceof BComponent)
        ? Ords.stationOrd((BComponent) mounted) : null;
    if (childOrd == null) childOrd = parentOrd + "/" + resolvedNameFinal;

    JSONObject result = new JSONObject();
    result.put("extensionOrd", childOrd);
    result.put("displayName",  resolvedNameFinal);
    result.put("requestedName", name);
    result.put("resolvedName",  resolvedNameFinal);
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
