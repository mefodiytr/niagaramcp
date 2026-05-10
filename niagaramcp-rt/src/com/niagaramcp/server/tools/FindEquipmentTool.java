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

import java.util.Locale;

/** Search equipment by name/aliases/id (case-insensitive substring). */
public final class FindEquipmentTool implements Tool {

  @Override public String name()        { return "findEquipment"; }
  @Override public String description() {
    return "Find equipment matching a query string. Searches name + aliases + id, " +
           "case-insensitive substring. Returns ranked matches.";
  }
  @Override public String schemaJson() {
    return "{\"type\":\"object\",\"properties\":{" +
           "\"query\":{\"type\":\"string\"}," +
           "\"limit\":{\"type\":\"integer\"}}," +
           "\"required\":[\"query\"]}";
  }

  @Override
  public String call(JSONObject args) throws Exception {
    String q = args.getString("query").toLowerCase(Locale.ROOT);
    int limit = args.optInt("limit", 50);
    KnowledgeStore ks = BMcpPlatformService.getKnowledgeStore();
    if (ks == null) throw new IllegalStateException("Knowledge store not available");

    JSONArray hits = new JSONArray();
    int count = 0;
    for (Equipment e : ks.getModel().equipment) {
      int score = scoreMatch(e, q);
      if (score == 0) continue;
      if (count >= limit) break;
      JSONObject h = new JSONObject();
      h.put("id", e.id);
      h.put("name", e.name == null ? "" : e.name);
      h.put("type", e.type == null ? "" : e.type);
      h.put("space", e.space == null ? "" : e.space);
      h.put("ord", e.ord == null ? "" : e.ord);
      h.put("score", score);
      hits.put(h);
      count++;
    }
    JSONObject out = new JSONObject();
    out.put("query", q);
    out.put("count", count);
    out.put("hits", hits);
    return out.toString();
  }

  /** Higher = better match. Exact name match > exact alias > substring in name > substring in alias > id. */
  private static int scoreMatch(Equipment e, String q) {
    if (e.id != null && e.id.toLowerCase(Locale.ROOT).equals(q)) return 100;
    if (e.name != null && e.name.toLowerCase(Locale.ROOT).equals(q)) return 90;
    for (String a : e.aliases) {
      if (a != null && a.toLowerCase(Locale.ROOT).equals(q)) return 80;
    }
    if (e.name != null && e.name.toLowerCase(Locale.ROOT).indexOf(q) >= 0) return 50;
    for (String a : e.aliases) {
      if (a != null && a.toLowerCase(Locale.ROOT).indexOf(q) >= 0) return 40;
    }
    if (e.id != null && e.id.toLowerCase(Locale.ROOT).indexOf(q) >= 0) return 30;
    return 0;
  }
}
