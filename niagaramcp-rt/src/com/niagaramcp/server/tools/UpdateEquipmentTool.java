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

/** Update fields on existing {@link Equipment}. */
public final class UpdateEquipmentTool implements Tool {

  @Override public String name()        { return "updateEquipment"; }
  @Override public String getCategory() { return "walkthrough-write"; }
  @Override public String description() {
    return "Update fields on an existing equipment entry. points (if supplied) merges " +
           "into existing role->ord map; pass empty object {} to leave unchanged.";
  }
  @Override public String schemaJson() {
    return "{\"type\":\"object\",\"properties\":{" +
           "\"id\":{\"type\":\"string\"}," +
           "\"name\":{\"type\":\"string\"}," +
           "\"aliases\":{\"type\":\"array\"}," +
           "\"type\":{\"type\":\"string\"}," +
           "\"space\":{\"type\":\"string\"}," +
           "\"ord\":{\"type\":\"string\"}," +
           "\"description\":{\"type\":\"string\"}," +
           "\"points\":{\"type\":\"object\"}," +
           "\"notes\":{\"type\":\"array\"}}," +
           "\"required\":[\"id\"]}";
  }

  @Override
  public String call(JSONObject args) throws Exception {
    String id = args.getString("id");
    KnowledgeStore ks = BMcpPlatformService.getKnowledgeStore();
    if (ks == null) throw new IllegalStateException("Knowledge store not available");
    Equipment e = ks.getModel().getEquipment(id);
    if (e == null) throw new IllegalArgumentException("Unknown equipment: " + id);

    if (args.has("name"))        e.name        = args.optString("name", e.name);
    if (args.has("type"))        e.type        = args.optString("type", e.type);
    if (args.has("space"))       e.space       = args.optString("space", e.space);
    if (args.has("ord"))         e.ord         = args.optString("ord", e.ord);
    if (args.has("description")) e.description = args.optString("description", e.description);
    if (args.has("aliases")) {
      JSONArray al = args.getJSONArray("aliases");
      e.aliases.clear();
      for (int i = 0; i < al.length(); i++) e.aliases.add(al.getString(i));
    }
    if (args.has("notes")) {
      JSONArray n = args.getJSONArray("notes");
      e.notes.clear();
      for (int i = 0; i < n.length(); i++) e.notes.add(n.getString(i));
    }
    if (args.has("points")) {
      JSONObject pts = args.getJSONObject("points");
      for (String role : pts.keySet()) e.points.put(role, pts.getString(role));
    }
    ks.save("updateEquipment", id, null);

    JSONObject r = new JSONObject();
    r.put("ok", true);
    r.put("id", id);
    return r.toString();
  }
}
