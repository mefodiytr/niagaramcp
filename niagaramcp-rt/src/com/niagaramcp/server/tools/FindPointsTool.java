/*
 * Copyright 2026 niagaramcp contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.niagaramcp.server.tools;

import com.niagaramcp.json.JSONArray;
import com.niagaramcp.json.JSONObject;
import com.niagaramcp.server.BMcpPlatformService;
import com.niagaramcp.server.knowledge.Equipment;
import com.niagaramcp.server.knowledge.KnowledgeStore;
import com.niagaramcp.server.knowledge.StandalonePoint;

import java.util.Locale;
import java.util.Map;

/** Search points across equipment.points and stand-alone points by role/kind/text. */
public final class FindPointsTool implements Tool {

  @Override public String name()        { return "findPoints"; }
  @Override public String description() {
    return "Search points by role (e.g. supply_air_temp), kind (temperature/boolean/...), " +
           "or substring against equipment id/name/aliases. At least one of role/kind/text " +
           "is required.";
  }
  @Override public String schemaJson() {
    return "{\"type\":\"object\",\"properties\":{" +
           "\"role\":{\"type\":\"string\"}," +
           "\"kind\":{\"type\":\"string\"}," +
           "\"text\":{\"type\":\"string\"}," +
           "\"limit\":{\"type\":\"integer\"}}}";
  }

  @Override
  public String call(JSONObject args) throws Exception {
    String role = lower(args.optString("role", null));
    String kind = lower(args.optString("kind", null));
    String text = lower(args.optString("text", null));
    int limit   = args.optInt("limit", 100);
    if (role == null && kind == null && text == null) {
      throw new IllegalArgumentException("At least one of role/kind/text is required");
    }
    KnowledgeStore ks = BMcpPlatformService.getKnowledgeStore();
    if (ks == null) throw new IllegalStateException("Knowledge store not available");

    JSONArray hits = new JSONArray();
    int count = 0;
    // Equipment points
    for (Equipment e : ks.getModel().equipment) {
      if (text != null && !matchesEquipmentText(e, text)) continue;
      for (Map.Entry<String,String> pe : e.points.entrySet()) {
        if (count >= limit) break;
        if (role != null && !role.equals(pe.getKey().toLowerCase(Locale.ROOT))) continue;
        // kind filter would need EquipmentType lookup of the role; simplified: skip kind for equipment-points
        if (kind != null) continue;
        JSONObject h = new JSONObject();
        h.put("source", "equipment");
        h.put("equipmentId", e.id);
        h.put("role", pe.getKey());
        h.put("ord", pe.getValue());
        hits.put(h);
        count++;
      }
    }
    // Standalone
    for (StandalonePoint p : ks.getModel().standalonePoints) {
      if (count >= limit) break;
      if (role != null && (p.roleInSpace == null
          || !role.equals(p.roleInSpace.toLowerCase(Locale.ROOT)))) continue;
      if (kind != null && (p.kind == null || !kind.equals(p.kind.toLowerCase(Locale.ROOT)))) continue;
      if (text != null && !matchesPointText(p, text)) continue;
      JSONObject h = new JSONObject();
      h.put("source", "standalone");
      h.put("id", p.id);
      h.put("name", p.name == null ? "" : p.name);
      h.put("kind", p.kind == null ? "" : p.kind);
      h.put("ord", p.ord == null ? "" : p.ord);
      h.put("space", p.space == null ? "" : p.space);
      hits.put(h);
      count++;
    }

    JSONObject out = new JSONObject();
    out.put("count", count);
    out.put("hits", hits);
    return out.toString();
  }

  private static String lower(String s) {
    return (s == null || s.isEmpty()) ? null : s.toLowerCase(Locale.ROOT);
  }

  private static boolean matchesEquipmentText(Equipment e, String t) {
    if (e.id != null && e.id.toLowerCase(Locale.ROOT).indexOf(t) >= 0) return true;
    if (e.name != null && e.name.toLowerCase(Locale.ROOT).indexOf(t) >= 0) return true;
    for (String a : e.aliases) if (a != null && a.toLowerCase(Locale.ROOT).indexOf(t) >= 0) return true;
    return false;
  }

  private static boolean matchesPointText(StandalonePoint p, String t) {
    if (p.id != null && p.id.toLowerCase(Locale.ROOT).indexOf(t) >= 0) return true;
    if (p.name != null && p.name.toLowerCase(Locale.ROOT).indexOf(t) >= 0) return true;
    for (String a : p.aliases) if (a != null && a.toLowerCase(Locale.ROOT).indexOf(t) >= 0) return true;
    return false;
  }
}
