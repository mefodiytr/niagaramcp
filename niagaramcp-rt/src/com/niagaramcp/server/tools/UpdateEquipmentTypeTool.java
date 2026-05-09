/*
 * Copyright 2026 niagaramcp contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.niagaramcp.server.tools;

import com.niagaramcp.json.JSONArray;
import com.niagaramcp.json.JSONObject;
import com.niagaramcp.server.BMcpPlatformService;
import com.niagaramcp.server.knowledge.EquipmentType;
import com.niagaramcp.server.knowledge.KnowledgeStore;

import java.util.LinkedHashMap;
import java.util.Map;

/** Update an existing {@link EquipmentType}. */
public final class UpdateEquipmentTypeTool implements Tool {

  @Override public String name()        { return "updateEquipmentType"; }
  @Override public String description() {
    return "Update fields of an existing equipment_type. typical_points (if supplied) " +
           "REPLACES the existing list. To append a single role, use the dedicated " +
           "assignPointToEquipment tool on a specific equipment instead.";
  }
  @Override public String schemaJson() {
    return "{\"type\":\"object\",\"properties\":{" +
           "\"id\":{\"type\":\"string\"}," +
           "\"name\":{\"type\":\"string\"}," +
           "\"aliases\":{\"type\":\"array\"}," +
           "\"description\":{\"type\":\"string\"}," +
           "\"extends\":{\"type\":\"string\"}," +
           "\"typical_points\":{\"type\":\"array\"}}," +
           "\"required\":[\"id\"]}";
  }

  @Override
  public String call(JSONObject args) throws Exception {
    String id = args.getString("id");
    KnowledgeStore ks = BMcpPlatformService.getKnowledgeStore();
    if (ks == null) throw new IllegalStateException("Knowledge store not available");
    EquipmentType e = ks.getModel().getEquipmentType(id);
    if (e == null) throw new IllegalArgumentException("Unknown equipment_type: " + id);

    if (args.has("name"))        e.name        = args.optString("name", e.name);
    if (args.has("description")) e.description = args.optString("description", e.description);
    if (args.has("extends"))     e.extendsId   = args.optString("extends", e.extendsId);
    if (args.has("aliases")) {
      JSONArray al = args.getJSONArray("aliases");
      e.aliases.clear();
      for (int i = 0; i < al.length(); i++) e.aliases.add(al.getString(i));
    }
    if (args.has("typical_points")) {
      JSONArray tp = args.getJSONArray("typical_points");
      e.typicalPoints.clear();
      for (int i = 0; i < tp.length(); i++) {
        JSONObject tpo = tp.getJSONObject(i);
        Map<String,Object> m = new LinkedHashMap<String,Object>();
        for (String key : tpo.keySet()) m.put(key, tpo.get(key));
        e.typicalPoints.add(EquipmentType.TypicalPoint.fromMap(m));
      }
    }
    ks.save("updateEquipmentType", id, null);

    JSONObject r = new JSONObject();
    r.put("ok", true);
    r.put("id", id);
    return r.toString();
  }
}
