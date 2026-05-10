/*
 * Copyright 2026 niagaramcp contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.niagaramcp.server.tools;

import com.niagaramcp.json.JSONArray;
import com.niagaramcp.json.JSONObject;
import com.niagaramcp.server.BMcpPlatformService;
import com.niagaramcp.server.knowledge.EquipmentType;
import com.niagaramcp.server.knowledge.KnowledgeModel;
import com.niagaramcp.server.knowledge.KnowledgeStore;

import java.util.LinkedHashMap;
import java.util.Map;

/** Create a new {@link EquipmentType} in the knowledge model. */
public final class CreateEquipmentTypeTool implements Tool {

  @Override public String name()        { return "createEquipmentType"; }
  @Override public String description() {
    return "Create a new equipment_type (template for AHU/Chiller/Pump/...). " +
           "extends references another type id (cycle-checked).";
  }
  @Override public String schemaJson() {
    return "{\"type\":\"object\",\"properties\":{" +
           "\"id\":{\"type\":\"string\"}," +
           "\"name\":{\"type\":\"string\"}," +
           "\"aliases\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}," +
           "\"description\":{\"type\":\"string\"}," +
           "\"extends\":{\"type\":\"string\"}," +
           "\"typical_points\":{\"type\":\"array\",\"items\":{\"type\":\"object\"}}}," +
           "\"required\":[\"id\",\"name\"]}";
  }

  @Override
  public String call(JSONObject args) throws Exception {
    String id = args.getString("id");
    String name = args.getString("name");
    KnowledgeStore ks = BMcpPlatformService.getKnowledgeStore();
    if (ks == null) throw new IllegalStateException("Knowledge store not available");
    KnowledgeModel km = ks.getModel();
    if (km.getEquipmentType(id) != null) {
      throw new IllegalArgumentException("EquipmentType already exists: " + id);
    }
    String extendsId = args.optString("extends", null);
    if (extendsId != null && !extendsId.isEmpty() && km.getEquipmentType(extendsId) == null) {
      throw new IllegalArgumentException("Unknown extends: " + extendsId);
    }

    EquipmentType e = new EquipmentType();
    e.id = id;
    e.name = name;
    e.description = args.optString("description", null);
    e.extendsId = (extendsId != null && extendsId.isEmpty()) ? null : extendsId;
    JSONArray al = args.optJSONArray("aliases");
    if (al != null) for (int i = 0; i < al.length(); i++) e.aliases.add(al.getString(i));
    JSONArray tp = args.optJSONArray("typical_points");
    if (tp != null) {
      for (int i = 0; i < tp.length(); i++) {
        JSONObject tpo = tp.getJSONObject(i);
        Map<String,Object> tpm = new LinkedHashMap<String,Object>();
        for (String key : tpo.keySet()) tpm.put(key, tpo.get(key));
        e.typicalPoints.add(EquipmentType.TypicalPoint.fromMap(tpm));
      }
    }
    km.equipmentTypes.add(e);
    ks.save("createEquipmentType", id, null);

    JSONObject r = new JSONObject();
    r.put("ok", true);
    r.put("id", id);
    return r.toString();
  }
}
