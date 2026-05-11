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
import com.niagaramcp.json.JSONArray;
import com.niagaramcp.json.JSONObject;
import com.niagaramcp.server.CallContext;
import com.niagaramcp.server.McpProtocol;
import com.niagaramcp.server.auth.UserContextGateway;

/**
 * Removes a {@link BComponent} from its parent slot, under the calling
 * user's Niagara permissions.
 *
 * <h3>Safety</h3>
 * Default {@code dryRun=true} — first call always reports what would
 * happen without mutating. Inbound link check (via
 * {@code component.getLinks()}) refuses removal if any other component
 * is reading from a slot on this one — operator must unlink first or
 * pass {@code force=true} to override (the override still fires the
 * check + reports it in the result for audit).
 *
 * <h3>Outbound link limitation</h3>
 * This tool only inspects INBOUND links (links stored on this
 * component as slots). Links where this component is the SOURCE live
 * on OTHER components elsewhere on the station and detecting them
 * requires a full station walk — too expensive for v0.5.1. Removing
 * a source-of-link will leave dangling links on the consumers; they
 * surface as broken refs after restart. Operator using
 * {@code force=true} accepts that risk. {@code linkSlots/unlinkSlots}
 * (commit 5) gives operators a way to clean dangling links explicitly.
 *
 * <h3>Args</h3>
 * <pre>
 * {
 *   "ord":     "station:|slot:/Drivers/BasementHVAC",  // required
 *   "dryRun":  true,                                   // optional, default true
 *   "force":   false                                   // optional, default false
 * }
 * </pre>
 *
 * <h3>Result</h3>
 * <pre>
 * {
 *   "ord":              "station:|slot:/Drivers/BasementHVAC",
 *   "removed":          true,        // false when dryRun
 *   "wouldRemove":      true,        // dryRun's preview
 *   "inboundLinkCount": 0,
 *   "sampleSourceOrds": []
 * }
 * </pre>
 *
 * <h3>Error codes</h3>
 * <ul>
 *   <li>{@code -32602} missing/invalid args</li>
 *   <li>{@code -32006} ord not resolvable / not a BComponent</li>
 *   <li>{@code -32010} permission denied (PermissionException → wrapper)</li>
 *   <li>{@code -32011} user-Context required (pre-dispatch)</li>
 *   <li>{@code -32013} ERR_COMPONENT_HAS_INBOUND_LINKS — refused due
 *       to inbound links AND {@code force=false}. data carries
 *       {@code {ord, inboundLinkCount, sampleSourceOrds[]}} — caller
 *       can decide to retry with {@code force=true}.</li>
 * </ul>
 *
 * <p>Removing the station root or any service top-level component
 * will hit -32010 from Niagara's own protections.
 */
public final class RemoveComponentTool implements Tool {

  /** How many source-ord samples to include in the inbound-link error data. */
  private static final int SAMPLE_LIMIT = 5;

  @Override public String name()                  { return "removeComponent"; }
  @Override public String getCategory()           { return "write"; }
  @Override public boolean requiresUserContext()  { return true; }
  @Override public ToolAnnotations annotations()  { return ToolAnnotations.DESTRUCTIVE; }

  @Override public String description() {
    return "Remove a BComponent from its parent slot, under the calling user's permissions. " +
           "Default dryRun=true; refuses if inbound links exist unless force=true. " +
           "Does NOT detect outbound links where the target is the source — those become " +
           "dangling refs (use linkSlots tooling to clean up first). DESTRUCTIVE: " +
           "MCP-aware clients should warn the user before invoking with dryRun=false.";
  }

  @Override public String schemaJson() {
    return ToolSchemaHelpers.objectSchema(
        new String[]{"ord"},
        "ord",    ToolSchemaHelpers.ordParam(
            "Ord of the BComponent to remove (e.g. 'station:|slot:/Drivers/BasementHVAC')."),
        "dryRun", ToolSchemaHelpers.boolParam(
            "If true (default), report what would happen without mutating."),
        "force",  ToolSchemaHelpers.boolParam(
            "If true, remove even when inbound links exist (default false)."));
  }

  @Override
  public String call(JSONObject args) throws Exception {
    final String ordStr = requiredString(args, "ord");
    final boolean dryRun = args.optBoolean("dryRun", true);
    final boolean force  = args.optBoolean("force",  false);

    BUser user = CallContext.user();
    if (user == null) {
      throw new McpProtocol.RpcException(McpProtocol.ERR_USER_NOT_FOUND,
          "removeComponent requires a user-Context bearer", oneField("tool", name()));
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

    BComponent parent = (BComponent) target.getParent();
    if (parent == null) {
      throw new McpProtocol.RpcException(McpProtocol.ERR_INVALID_PARAMS,
          "Cannot remove a root component (no parent)", oneField("ord", ordStr));
    }

    // Inbound link check — even on dryRun, surface the count.
    BLink[] inbound = target.getLinks();
    int inboundCount = (inbound == null) ? 0 : inbound.length;
    JSONArray sampleSources = new JSONArray();
    if (inbound != null) {
      for (int i = 0; i < inbound.length && i < SAMPLE_LIMIT; i++) {
        BComponent src = inbound[i].getSourceComponent();
        String s = Ords.stationOrd(src);
        sampleSources.put(s == null ? "" : s);
      }
    }

    if (inboundCount > 0 && !force) {
      JSONObject d = new JSONObject();
      d.put("ord", ordStr);
      d.put("inboundLinkCount", inboundCount);
      d.put("sampleSourceOrds", sampleSources);
      throw new McpProtocol.RpcException(
          McpProtocol.ERR_COMPONENT_HAS_INBOUND_LINKS,
          "Component has " + inboundCount + " inbound link(s); pass force=true to override " +
          "or unlink first via unlinkSlots", d);
    }

    JSONObject result = new JSONObject();
    result.put("ord", ordStr);
    result.put("inboundLinkCount", inboundCount);
    result.put("sampleSourceOrds", sampleSources);

    if (dryRun) {
      result.put("removed", false);
      result.put("wouldRemove", true);
      return result.toString();
    }

    final Property targetProperty = target.getPropertyInParent();
    if (targetProperty == null) {
      throw new McpProtocol.RpcException(McpProtocol.ERR_INTERNAL,
          "Could not resolve component's slot in its parent", oneField("ord", ordStr));
    }

    UserContextGateway.OpDesc op =
        UserContextGateway.OpDesc.of(name(), ordStr, "remove");
    String sessionId = CallContext.sessionId();

    UserContextGateway.run(user, op, args, sessionId, cx -> {
      parent.remove(targetProperty, cx);
      return null;
    });

    result.put("removed", true);
    result.put("wouldRemove", true);
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
