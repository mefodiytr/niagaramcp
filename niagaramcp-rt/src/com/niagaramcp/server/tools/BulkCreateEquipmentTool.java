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

import java.util.ArrayList;
import java.util.List;

/** Atomic batch create of multiple {@link Equipment} entries (validate-then-commit). */
public final class BulkCreateEquipmentTool implements Tool {

  @Override public String name()        { return "bulkCreateEquipment"; }
  @Override public String description() {
    return "Create multiple equipment entries atomically. Validates all entries before " +
           "writing any; either all succeed or none are added.";
  }
  @Override public String schemaJson() {
    return "{\"type\":\"object\",\"properties\":{" +
           "\"items\":{\"type\":\"array\",\"items\":{\"type\":\"object\"}}}," +
           "\"required\":[\"items\"]}";
  }

  @Override
  public String call(JSONObject args) throws Exception {
    JSONArray items = args.getJSONArray("items");
    if (items.length() == 0) throw new IllegalArgumentException("items must be non-empty");

    KnowledgeStore ks = BMcpPlatformService.getKnowledgeStore();
    if (ks == null) throw new IllegalStateException("Knowledge store not available");
    KnowledgeModel km = ks.getModel();

    // Validate all
    List<Equipment> staged = new ArrayList<Equipment>(items.length());
    for (int i = 0; i < items.length(); i++) {
      JSONObject o = items.getJSONObject(i);
      Equipment e = new Equipment();
      e.id   = o.getString("id");
      e.name = o.getString("name");
      e.type = o.getString("type");
      e.ord  = o.getString("ord");
      e.space       = o.optString("space", null);
      e.description = o.optString("description", null);
      JSONArray al = o.optJSONArray("aliases");
      if (al != null) for (int j = 0; j < al.length(); j++) e.aliases.add(al.getString(j));
      JSONObject pts = o.optJSONObject("points");
      if (pts != null) for (String role : pts.keySet()) e.points.put(role, pts.getString(role));

      if (km.getEquipment(e.id) != null)
        throw new IllegalArgumentException("Equipment already exists: " + e.id);
      if (km.getEquipmentType(e.type) == null)
        throw new IllegalArgumentException("Unknown equipment_type: " + e.type + " (item " + i + ")");
      if (e.space != null && !e.space.isEmpty() && km.getSpace(e.space) == null)
        throw new IllegalArgumentException("Unknown space: " + e.space + " (item " + i + ")");
      // Check for duplicate ids inside the batch itself
      for (Equipment prev : staged) {
        if (prev.id.equals(e.id))
          throw new IllegalArgumentException("Duplicate id within batch: " + e.id);
      }
      staged.add(e);
    }

    // Commit all
    for (Equipment e : staged) km.equipment.add(e);
    ks.save("bulkCreateEquipment", "+" + staged.size(), null);

    JSONObject r = new JSONObject();
    r.put("ok", true);
    r.put("created", staged.size());
    return r.toString();
  }
}
