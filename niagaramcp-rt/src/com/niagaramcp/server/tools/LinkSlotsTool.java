/*
 * Copyright 2026 niagaramcp contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.niagaramcp.server.tools;

import javax.baja.sys.BComponent;
import javax.baja.sys.BConversionLink;
import javax.baja.sys.BLink;
import javax.baja.sys.BObject;
import javax.baja.sys.BValue;
import javax.baja.sys.LinkCheck;
import javax.baja.sys.Property;
import javax.baja.sys.Slot;
import javax.baja.sys.Sys;
import javax.baja.sys.Type;
import javax.baja.user.BUser;
import javax.baja.util.BConverter;
import com.niagaramcp.json.JSONObject;
import com.niagaramcp.server.CallContext;
import com.niagaramcp.server.McpProtocol;
import com.niagaramcp.server.auth.UserContextGateway;

/**
 * Wires a source slot's value into a sink slot via a {@link BLink}
 * (or a {@link BConversionLink} when a {@code converterType} is given),
 * stored as a child slot on the sink component, under user-Context.
 *
 * <h3>Args</h3>
 * <pre>
 * {
 *   "sourceOrd":     "station:|slot:/Logic/RoomTemp",   // required
 *   "sourceSlot":    "out",                              // required
 *   "sinkOrd":       "station:|slot:/Logic/Setpoint",   // required
 *   "sinkSlot":      "in1",                              // required
 *   "linkName":      "RoomTempLink",                     // optional, auto-generated
 *   "converterType": "baja:NullConverter"                // optional, "module:Type"
 * }
 * </pre>
 *
 * <h3>Result</h3>
 * <pre>
 * {
 *   "linkOrd":   "station:|slot:/Logic/Setpoint/RoomTempLink",
 *   "linkType":  "link" | "conversionLink",
 *   "converter": "baja:NullConverter",   // present only when converterType was given
 *   "sourceOrd": "...", "sourceSlot": "out",
 *   "sinkOrd":   "...", "sinkSlot":  "in1",
 *   "linkCheckNote": "..."               // present only if checkLink was bypassed
 * }
 * </pre>
 *
 * <h3>Type-mismatch policy</h3>
 * Calls {@code sink.checkLink(source, sourceSlot, sinkSlot, cx)} BEFORE
 * any mutation. {@link LinkCheck#isValid()} false:
 * <ul>
 *   <li>no {@code converterType} → refuse with {@code -32016
 *       ERR_LINK_TYPE_MISMATCH} carrying {@code data{sourceOrd, sinkOrd,
 *       reason}}. Niagara's own compatibility predicate is the source of
 *       truth.</li>
 *   <li>with {@code converterType} → the caller is explicitly bridging the
 *       mismatch; the check result is recorded in the result as
 *       {@code linkCheckNote} and the add proceeds with a
 *       {@code BConversionLink}. If the named converter can't actually
 *       bridge the pair the link is added but faults at runtime (inspect
 *       the link slot to see), or {@code add()} rejects it → {@code -32603}.</li>
 * </ul>
 * Per design Q6 there is <b>no</b> {@code convert: true} auto-pick — the
 * converter must be named explicitly (no surprise auto-conversions, and
 * there is no stable public converter-registry to walk anyway).
 *
 * <h3>Errors</h3>
 * <ul>
 *   <li>{@code -32602} missing arg / link slot already exists /
 *       converterType doesn't resolve to a BConverter</li>
 *   <li>{@code -32006} sourceOrd or sinkOrd not resolvable / not BComponent</li>
 *   <li>{@code -32602} sourceSlot or sinkSlot doesn't exist on its component</li>
 *   <li>{@code -32005} converterType doesn't load / abstract</li>
 *   <li>{@code -32016} type mismatch from Niagara's checkLink (no converterType)</li>
 *   <li>{@code -32010} permission denied</li>
 *   <li>{@code -32011} user-Context required</li>
 * </ul>
 */
public final class LinkSlotsTool implements Tool {

  @Override public String name()                  { return "linkSlots"; }
  @Override public String getCategory()           { return "write"; }
  @Override public boolean requiresUserContext()  { return true; }
  @Override public ToolAnnotations annotations()  { return ToolAnnotations.MUTATION; }

  @Override public String description() {
    return "Wire a source slot to a sink slot via BLink (or BConversionLink when a " +
           "converterType is given), stored on the sink under user-Context. Args: " +
           "{sourceOrd, sourceSlot, sinkOrd, sinkSlot, linkName?, converterType?}. " +
           "Calls Niagara's checkLink first; type mismatch without a converterType → " +
           "-32016. converterType must be a 'module:Type' that resolves to a BConverter.";
  }

  @Override public String schemaJson() {
    return ToolSchemaHelpers.objectSchema(
        new String[]{"sourceOrd", "sourceSlot", "sinkOrd", "sinkSlot"},
        "sourceOrd",     ToolSchemaHelpers.ordParam("Component supplying the value."),
        "sourceSlot",    ToolSchemaHelpers.stringParam("Slot name on the source."),
        "sinkOrd",       ToolSchemaHelpers.ordParam("Component receiving the value (link is stored here)."),
        "sinkSlot",      ToolSchemaHelpers.stringParam("Slot name on the sink."),
        "linkName",      ToolSchemaHelpers.stringParam(
            "Optional name for the link slot; default = sourceComponent + sourceSlot suffix."),
        "converterType", ToolSchemaHelpers.stringParam(
            "Optional 'module:Type' of a BConverter; when set, a BConversionLink is used " +
            "and the checkLink type-mismatch refusal is bypassed (e.g. 'baja:NullConverter')."));
  }

  @Override
  public String call(JSONObject args) throws Exception {
    final String sourceOrdStr = requiredString(args, "sourceOrd");
    final String sourceSlotName = requiredString(args, "sourceSlot");
    final String sinkOrdStr   = requiredString(args, "sinkOrd");
    final String sinkSlotName = requiredString(args, "sinkSlot");
    final String linkNameArg  = args.optString("linkName", "");
    final String converterTypeArg = args.optString("converterType", "");

    BUser user = CallContext.user();
    if (user == null) {
      throw new McpProtocol.RpcException(McpProtocol.ERR_USER_NOT_FOUND,
          "linkSlots requires a user-Context bearer", oneField("tool", name()));
    }

    BComponent source = resolveComp(sourceOrdStr, "sourceOrd");
    BComponent sink   = resolveComp(sinkOrdStr,   "sinkOrd");

    Slot sourceSlot = source.getSlot(sourceSlotName);
    if (sourceSlot == null) {
      JSONObject d = new JSONObject();
      d.put("ord", sourceOrdStr); d.put("slot", sourceSlotName);
      throw new McpProtocol.RpcException(McpProtocol.ERR_INVALID_PARAMS,
          "sourceSlot \"" + sourceSlotName + "\" not found on " + sourceOrdStr, d);
    }
    Slot sinkSlot = sink.getSlot(sinkSlotName);
    if (sinkSlot == null) {
      JSONObject d = new JSONObject();
      d.put("ord", sinkOrdStr); d.put("slot", sinkSlotName);
      throw new McpProtocol.RpcException(McpProtocol.ERR_INVALID_PARAMS,
          "sinkSlot \"" + sinkSlotName + "\" not found on " + sinkOrdStr, d);
    }

    // Optional converter — load and validate before any mutation.
    final BConverter converter;
    if (converterTypeArg.isEmpty()) {
      converter = null;
    } else {
      Type ct;
      try {
        ct = Sys.getType(converterTypeArg);
      } catch (Exception e) {
        throw new McpProtocol.RpcException(McpProtocol.ERR_SCHEMA_VALIDATION,
            "Cannot load converterType: " + e.getMessage(), oneField("converterType", converterTypeArg));
      }
      BObject ci = ct.getInstance();
      if (!(ci instanceof BConverter)) {
        JSONObject d = new JSONObject();
        d.put("converterType", converterTypeArg);
        d.put("actualType", (ci == null) ? "null" : ci.getType().getTypeSpec().toString());
        throw new McpProtocol.RpcException(McpProtocol.ERR_INVALID_PARAMS,
            "converterType does not resolve to a javax.baja.util.BConverter", d);
      }
      converter = (BConverter) ci;
    }

    // Type compatibility check — under default cx (read-only).
    String linkCheckNote = null;
    LinkCheck check = sink.checkLink(source, sourceSlot, sinkSlot, null);
    if (check != null && !check.isValid()) {
      if (converter == null) {
        JSONObject d = new JSONObject();
        d.put("sourceOrd", sourceOrdStr);
        d.put("sourceSlot", sourceSlotName);
        d.put("sinkOrd", sinkOrdStr);
        d.put("sinkSlot", sinkSlotName);
        d.put("reason", check.getInvalidReason());
        throw new McpProtocol.RpcException(McpProtocol.ERR_LINK_TYPE_MISMATCH,
            "Link type mismatch: " + check.getInvalidReason(), d);
      }
      // converter supplied → caller is bridging the mismatch deliberately.
      linkCheckNote = "checkLink reported invalid (" + check.getInvalidReason()
                    + ") — bridging with " + converterTypeArg;
    }

    // Auto-pick a name if not provided. Format mirrors Workbench's:
    //   <SourceComponentName><SourceSlotNameCapitalized>Link
    final String linkName;
    if (linkNameArg.isEmpty()) {
      String autoBase = source.getName() + capitalize(sourceSlotName);
      String tryName = autoBase + "Link";
      int n = 2;
      while (sink.get(tryName) != null) {
        tryName = autoBase + "Link_" + n++;
      }
      linkName = tryName;
    } else {
      if (sink.get(linkNameArg) != null) {
        JSONObject d = new JSONObject();
        d.put("sinkOrd", sinkOrdStr); d.put("linkName", linkNameArg);
        throw new McpProtocol.RpcException(McpProtocol.ERR_INVALID_PARAMS,
            "linkName \"" + linkNameArg + "\" already exists on sink", d);
      }
      linkName = linkNameArg;
    }

    UserContextGateway.OpDesc op =
        UserContextGateway.OpDesc.of(name(), sinkOrdStr, "link");
    String sessionId = CallContext.sessionId();

    final Slot fSourceSlot = sourceSlot, fSinkSlot = sinkSlot;
    final BConverter fConverter = converter;
    final BComponent fSource = source, fSink = sink;
    final String fLinkName = linkName;
    UserContextGateway.run(user, op, args, sessionId, cx -> {
      BLink link = (fConverter != null)
          ? new BConversionLink(fSource, fSourceSlot, fSinkSlot, fConverter)
          : fSink.makeLink(fSource, fSourceSlot, fSinkSlot, cx);
      fSink.add(fLinkName, link, cx);
      return null;
    });

    // Fully-qualified link ord (e.g. "station:|slot:/Logic/Setpoint/RoomTempLink").
    // A BLink is a BRelation, not a BComponent — no getSlotPath() — so build it
    // from the sink's ord + the link slot name.
    String sinkDisplay = Ords.stationOrd(sink);
    String linkOrd = (sinkDisplay != null)
        ? sinkDisplay + "/" + linkName
        : sinkOrdStr + "/" + linkName;

    JSONObject result = new JSONObject();
    result.put("linkOrd",    linkOrd);
    result.put("linkType",   converter != null ? "conversionLink" : "link");
    if (converter != null) result.put("converter", converterTypeArg);
    result.put("sourceOrd",  sourceOrdStr);
    result.put("sourceSlot", sourceSlotName);
    result.put("sinkOrd",    sinkOrdStr);
    result.put("sinkSlot",   sinkSlotName);
    if (linkCheckNote != null) result.put("linkCheckNote", linkCheckNote);
    return result.toString();
  }

  // ---- helpers ----

  private static BComponent resolveComp(String ordStr, String fieldName) {
    BObject obj;
    try {
      obj = Ords.resolve(ordStr);
    } catch (Exception e) {
      throw new McpProtocol.RpcException(McpProtocol.ERR_ORD_NOT_RESOLVABLE,
          fieldName + " not resolvable: " + e.getMessage(), oneField(fieldName, ordStr));
    }
    if (!(obj instanceof BComponent)) {
      throw new McpProtocol.RpcException(McpProtocol.ERR_ORD_NOT_RESOLVABLE,
          fieldName + " does not point to a BComponent", oneField(fieldName, ordStr));
    }
    return (BComponent) obj;
  }

  private static String capitalize(String s) {
    if (s == null || s.isEmpty()) return s;
    return Character.toUpperCase(s.charAt(0)) + s.substring(1);
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
