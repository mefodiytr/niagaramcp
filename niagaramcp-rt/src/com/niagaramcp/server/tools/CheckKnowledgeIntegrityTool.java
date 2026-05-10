/*
 * Copyright 2026 niagaramcp contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.niagaramcp.server.tools;

import javax.baja.naming.BOrd;
import javax.baja.sys.BObject;
import com.niagaramcp.json.JSONArray;
import com.niagaramcp.json.JSONObject;
import com.niagaramcp.server.BMcpPlatformService;
import com.niagaramcp.server.knowledge.Equipment;
import com.niagaramcp.server.knowledge.KnowledgeStore;
import com.niagaramcp.server.knowledge.StandalonePoint;

import java.util.Map;

/** Resolve every ord referenced in the knowledge model; report broken references. */
public final class CheckKnowledgeIntegrityTool implements Tool {

  @Override public String name()        { return "checkKnowledgeIntegrity"; }
  @Override public String description() {
    return "Check that every ord in the knowledge model resolves on the running station. " +
           "Returns {totalRefs, validRefs, brokenRefs:[{equipment|point, role?, ord, reason}]}.";
  }
  @Override public String schemaJson() { return "{\"type\":\"object\",\"properties\":{}}"; }

  @Override
  public String call(JSONObject args) throws Exception {
    KnowledgeStore ks = BMcpPlatformService.getKnowledgeStore();
    if (ks == null) throw new IllegalStateException("Knowledge store not available");

    int totalRefs = 0, validRefs = 0;
    JSONArray broken = new JSONArray();

    for (Equipment e : ks.getModel().equipment) {
      if (e.ord != null && !e.ord.isEmpty()) {
        totalRefs++;
        String reason = tryResolve(e.ord);
        if (reason == null) validRefs++;
        else broken.put(brokenEntry(e.id, "ord", null, e.ord, reason));
      }
      for (Map.Entry<String,String> pe : e.points.entrySet()) {
        totalRefs++;
        String ord = pe.getValue();
        if (ord == null || ord.isEmpty()) {
          broken.put(brokenEntry(e.id, "points", pe.getKey(), "", "empty ord"));
          continue;
        }
        String reason = tryResolve(ord);
        if (reason == null) validRefs++;
        else broken.put(brokenEntry(e.id, "points", pe.getKey(), ord, reason));
      }
    }
    for (StandalonePoint p : ks.getModel().standalonePoints) {
      if (p.ord != null && !p.ord.isEmpty()) {
        totalRefs++;
        String reason = tryResolve(p.ord);
        if (reason == null) validRefs++;
        else broken.put(standaloneBroken(p.id, p.ord, reason));
      }
    }

    JSONObject out = new JSONObject();
    out.put("totalRefs", totalRefs);
    out.put("validRefs", validRefs);
    out.put("brokenCount", broken.length());
    out.put("brokenRefs", broken);
    return out.toString();
  }

  /** @return null if resolves, else human reason. */
  private static String tryResolve(String ordStr) {
    try {
      BObject obj = BOrd.make(ordStr).get();
      return obj == null ? "ord resolved to null" : null;
    } catch (Exception e) {
      return "ord not resolvable: " + e.getMessage();
    }
  }

  private static JSONObject brokenEntry(String equipmentId, String field, String role, String ord, String reason) {
    JSONObject j = new JSONObject();
    j.put("equipment", equipmentId);
    j.put("field", field);
    if (role != null) j.put("role", role);
    j.put("ord", ord);
    j.put("reason", reason);
    return j;
  }

  private static JSONObject standaloneBroken(String pointId, String ord, String reason) {
    JSONObject j = new JSONObject();
    j.put("standalonePoint", pointId);
    j.put("ord", ord);
    j.put("reason", reason);
    return j;
  }
}
