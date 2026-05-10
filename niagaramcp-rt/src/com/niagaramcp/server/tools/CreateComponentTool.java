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
 * Reference write-tool: creates a fresh {@link BComponent} of a given
 * Type as a new child slot of an existing {@link BComponent}, under
 * the calling user's Niagara permissions.
 *
 * <p>Args:
 * <pre>
 * {
 *   "parentOrd":      "station:|slot:/Drivers",  // required
 *   "type":           "baja:Folder",             // required, "module:TypeName"
 *   "name":           "BasementHVAC",            // required
 *   "nameStrategy":   "fail" | "suffix"          // optional, default "fail"
 * }
 * </pre>
 *
 * <p>Returns (auto-promoted to {@code structuredContent} by the
 * protocol layer):
 * <pre>
 * {
 *   "ord":            "station:|slot:/Drivers/BasementHVAC",
 *   "displayName":    "BasementHVAC",
 *   "requestedName":  "BasementHVAC",
 *   "resolvedName":   "BasementHVAC"      // differs only when suffix applied
 * }
 * </pre>
 *
 * <p>{@code requiresUserContext()=true}, {@code annotations=MUTATION}.
 *
 * <p>Error envelope:
 * <ul>
 *   <li>{@code -32602 ERR_INVALID_PARAMS} — missing required arg /
 *       unknown nameStrategy / name collision under "fail" strategy</li>
 *   <li>{@code -32006 ERR_ORD_NOT_RESOLVABLE} — parentOrd doesn't
 *       resolve, or resolves to something that isn't a BComponent</li>
 *   <li>{@code -32005 ERR_SCHEMA_VALIDATION} — typeSpec doesn't load,
 *       or doesn't produce a BValue (some Types are abstract)</li>
 *   <li>{@code -32010 ERR_PERMISSION_DENIED} — user lacks add-permission
 *       on the parent (raised by UserContextGateway via
 *       PermissionException → -32010 wrapping)</li>
 *   <li>{@code -32011 ERR_USER_NOT_FOUND} — bearer matched apiToken
 *       (service identity) instead of a BUser; raised by McpProtocol
 *       pre-dispatch before this tool's call() body runs</li>
 * </ul>
 */
public final class CreateComponentTool implements Tool {

  @Override public String name()                  { return "createComponent"; }
  @Override public String getCategory()           { return "write"; }
  @Override public boolean requiresUserContext()  { return true; }
  @Override public ToolAnnotations annotations()  { return ToolAnnotations.MUTATION; }

  @Override public String description() {
    return "Create a new BComponent as a child of an existing component, under " +
           "the calling user's Niagara permissions. Args: parentOrd, type " +
           "(\"module:TypeName\"), name, optional nameStrategy (\"fail\" default | \"suffix\"). " +
           "Returns the new component's ord and the resolved name.";
  }

  @Override public String schemaJson() {
    return ToolSchemaHelpers.objectSchema(
        new String[]{"parentOrd", "type", "name"},
        "parentOrd", ToolSchemaHelpers.ordParam(
            "Ord of the parent BComponent (e.g. 'station:|slot:/Drivers')."),
        "type", ToolSchemaHelpers.stringParam(
            "Niagara type spec in 'module:TypeName' form (e.g. 'baja:Folder', 'control:NumericPoint')."),
        "name", ToolSchemaHelpers.stringParam(
            "Slot name for the new child. Must be a valid Niagara slot name."),
        "nameStrategy", makeNameStrategyParam());
  }

  private static JSONObject makeNameStrategyParam() {
    JSONObject p = new JSONObject();
    p.put("type", "string");
    com.niagaramcp.json.JSONArray en = new com.niagaramcp.json.JSONArray();
    en.put("fail"); en.put("suffix");
    p.put("enum", en);
    p.put("description",
        "Behaviour on name collision: \"fail\" (default) raises -32602; " +
        "\"suffix\" appends _2, _3, ... until a free name is found.");
    return p;
  }

  @Override
  public String call(JSONObject args) throws Exception {
    final String parentOrd  = requiredString(args, "parentOrd");
    final String typeSpec   = requiredString(args, "type");
    final String name       = requiredString(args, "name");
    final String strategy   = args.optString("nameStrategy", "fail");

    if (!"fail".equals(strategy) && !"suffix".equals(strategy)) {
      throw new McpProtocol.RpcException(McpProtocol.ERR_INVALID_PARAMS,
          "nameStrategy must be \"fail\" or \"suffix\"",
          dataField("nameStrategy", strategy));
    }

    BUser user = CallContext.user();
    if (user == null) {
      // Belt-and-braces: McpProtocol.callTool already gates this, but
      // direct calls (tests) might bypass dispatch.
      throw new McpProtocol.RpcException(McpProtocol.ERR_USER_NOT_FOUND,
          "createComponent requires a user-Context bearer", dataField("tool", name()));
    }

    // Resolve parent (read-only — no Context needed for ord resolution).
    BObject parentObj;
    try {
      parentObj = BOrd.make(parentOrd).get();
    } catch (Exception e) {
      throw new McpProtocol.RpcException(McpProtocol.ERR_ORD_NOT_RESOLVABLE,
          "parentOrd not resolvable: " + e.getMessage(),
          dataField("ord", parentOrd));
    }
    if (!(parentObj instanceof BComponent)) {
      throw new McpProtocol.RpcException(McpProtocol.ERR_ORD_NOT_RESOLVABLE,
          "parentOrd does not point to a BComponent",
          dataField("ord", parentOrd));
    }
    final BComponent parent = (BComponent) parentObj;

    // Load the requested type and instantiate.
    Type type;
    try {
      type = Sys.getType(typeSpec);
    } catch (Exception e) {
      throw new McpProtocol.RpcException(McpProtocol.ERR_SCHEMA_VALIDATION,
          "Cannot load type: " + e.getMessage(),
          dataField("type", typeSpec));
    }
    BObject inst = type.getInstance();
    if (!(inst instanceof BValue)) {
      throw new McpProtocol.RpcException(McpProtocol.ERR_SCHEMA_VALIDATION,
          "Type " + typeSpec + " does not produce a BValue (abstract type?)",
          dataField("type", typeSpec));
    }
    final BValue value = (BValue) inst;

    // Apply name-collision strategy. Lookup uses default cx (read-only).
    String resolvedName = name;
    if (parent.get(name) != null) {
      if ("fail".equals(strategy)) {
        JSONObject d = new JSONObject();
        d.put("parentOrd", parentOrd);
        d.put("name", name);
        throw new McpProtocol.RpcException(McpProtocol.ERR_INVALID_PARAMS,
            "Slot \"" + name + "\" already exists on parent (nameStrategy=fail)", d);
      }
      // suffix
      int n = 2;
      while (parent.get(name + "_" + n) != null) n++;
      resolvedName = name + "_" + n;
    }
    final String resolvedNameFinal = resolvedName;

    // Mutating part — under user-Context, audited, permission-wrapped.
    UserContextGateway.OpDesc op =
        UserContextGateway.OpDesc.of(name(), parentOrd, "add");
    String sessionId = CallContext.sessionId();

    Property added = UserContextGateway.run(user, op, args, sessionId, cx -> {
      return parent.add(resolvedNameFinal, value, cx);
    });

    // Build result
    String childOrd;
    try {
      childOrd = parent.getSlotPath().toString() + "/" + added.getName();
    } catch (Exception e) {
      childOrd = parentOrd + "/" + resolvedNameFinal;
    }

    JSONObject result = new JSONObject();
    result.put("ord",           childOrd);
    result.put("displayName",   resolvedNameFinal);
    result.put("requestedName", name);
    result.put("resolvedName",  resolvedNameFinal);
    return result.toString();
  }

  // ---- helpers ----

  private static String requiredString(JSONObject args, String key) {
    String v = args.optString(key, "");
    if (v == null || v.isEmpty()) {
      throw new McpProtocol.RpcException(McpProtocol.ERR_INVALID_PARAMS,
          "Missing required arg: " + key, dataField("missing", key));
    }
    return v;
  }

  private static JSONObject dataField(String k, String v) {
    JSONObject d = new JSONObject();
    d.put(k, v);
    return d;
  }
}
