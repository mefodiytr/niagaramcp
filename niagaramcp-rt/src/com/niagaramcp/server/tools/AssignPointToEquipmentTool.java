/*
 * Copyright 2026 niagaramcp contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.niagaramcp.server.tools;

import javax.baja.naming.BOrd;
import com.niagaramcp.json.JSONObject;
import com.niagaramcp.server.BMcpPlatformService;
import com.niagaramcp.server.knowledge.Equipment;
import com.niagaramcp.server.knowledge.KnowledgeStore;

/** Add or update a single role->ord mapping on an equipment. */
public final class AssignPointToEquipmentTool implements Tool {

  @Override public String name()        { return "assignPointToEquipment"; }
  @Override public String getCategory() { return "walkthrough-write"; }
  @Override public String description() {
    return "Assign a Niagara ord to a semantic role on an existing equipment. " +
           "Role is free-form (e.g. supply_air_temp); ord is validated for syntax only.";
  }
  @Override public String schemaJson() {
    return "{\"type\":\"object\",\"properties\":{" +
           "\"equipmentId\":{\"type\":\"string\"}," +
           "\"role\":{\"type\":\"string\"}," +
           "\"ord\":{\"type\":\"string\"}}," +
           "\"required\":[\"equipmentId\",\"role\",\"ord\"]}";
  }

  @Override
  public String call(JSONObject args) throws Exception {
    String eqId = args.getString("equipmentId");
    String role = args.getString("role");
    String ord  = args.getString("ord");

    // Syntactic validation only — do not call .get() (would resolve at runtime)
    try { BOrd.make(ord); } catch (Exception e) {
      throw new IllegalArgumentException("Invalid ord syntax: " + ord);
    }

    KnowledgeStore ks = BMcpPlatformService.getKnowledgeStore();
    if (ks == null) throw new IllegalStateException("Knowledge store not available");
    Equipment e = ks.getModel().getEquipment(eqId);
    if (e == null) throw new IllegalArgumentException("Unknown equipment: " + eqId);

    e.points.put(role, ord);
    ks.save("assignPointToEquipment", eqId + "/" + role, null);

    JSONObject r = new JSONObject();
    r.put("ok", true);
    r.put("equipmentId", eqId);
    r.put("role", role);
    return r.toString();
  }
}
