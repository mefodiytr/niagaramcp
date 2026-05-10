/*
 * Copyright 2026 niagaramcp contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.niagaramcp.server.tools;

import com.niagaramcp.json.JSONArray;
import com.niagaramcp.json.JSONObject;
import com.niagaramcp.server.BMcpPlatformService;
import com.niagaramcp.server.knowledge.Equipment;
import com.niagaramcp.server.knowledge.KnowledgeModel;
import com.niagaramcp.server.knowledge.KnowledgeStore;

/** Create a new {@link Equipment} (concrete unit) in the knowledge model. */
public final class CreateEquipmentTool implements Tool {

  @Override public String name()        { return "createEquipment"; }
  @Override public String description() {
    return "Create a new equipment instance (one specific AHU/Chiller/etc.). " +
           "type must reference an existing equipment_type. ord is the Niagara ord " +
           "of the equipment's root component.";
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
           "\"points\":{\"type\":\"object\"}}," +
           "\"required\":[\"id\",\"name\",\"type\",\"ord\"]}";
  }

  @Override
  public String call(JSONObject args) throws Exception {
    String id = args.getString("id");
    String name = args.getString("name");
    String type = args.getString("type");
    String ord  = args.getString("ord");

    KnowledgeStore ks = BMcpPlatformService.getKnowledgeStore();
    if (ks == null) throw new IllegalStateException("Knowledge store not available");
    KnowledgeModel km = ks.getModel();
    if (km.getEquipment(id) != null)            throw new IllegalArgumentException("Equipment already exists: " + id);
    if (km.getEquipmentType(type) == null)      throw new IllegalArgumentException("Unknown equipment_type: " + type);
    String space = args.optString("space", null);
    if (space != null && !space.isEmpty() && km.getSpace(space) == null) {
      throw new IllegalArgumentException("Unknown space: " + space);
    }

    Equipment e = new Equipment();
    e.id   = id;
    e.name = name;
    e.type = type;
    e.ord  = ord;
    e.space = (space != null && space.isEmpty()) ? null : space;
    e.description = args.optString("description", null);
    JSONArray al = args.optJSONArray("aliases");
    if (al != null) for (int i = 0; i < al.length(); i++) e.aliases.add(al.getString(i));
    JSONObject pts = args.optJSONObject("points");
    if (pts != null) {
      for (String role : pts.keySet()) e.points.put(role, pts.getString(role));
    }
    km.equipment.add(e);
    ks.save("createEquipment", id, null);

    JSONObject r = new JSONObject();
    r.put("ok", true);
    r.put("id", id);
    return r.toString();
  }
}
