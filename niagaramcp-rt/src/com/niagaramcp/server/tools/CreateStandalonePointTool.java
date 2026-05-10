/*
 * Copyright 2026 niagaramcp contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.niagaramcp.server.tools;

import com.niagaramcp.json.JSONArray;
import com.niagaramcp.json.JSONObject;
import com.niagaramcp.server.BMcpPlatformService;
import com.niagaramcp.server.knowledge.KnowledgeModel;
import com.niagaramcp.server.knowledge.KnowledgeStore;
import com.niagaramcp.server.knowledge.StandalonePoint;

/** Create a stand-alone point (sensor not bound to a specific equipment). */
public final class CreateStandalonePointTool implements Tool {

  @Override public String name()        { return "createStandalonePoint"; }
  @Override public String description() {
    return "Register a stand-alone point (e.g. ambient temperature/CO2 sensor) under a space.";
  }
  @Override public String schemaJson() {
    return "{\"type\":\"object\",\"properties\":{" +
           "\"id\":{\"type\":\"string\"}," +
           "\"name\":{\"type\":\"string\"}," +
           "\"aliases\":{\"type\":\"array\"}," +
           "\"space\":{\"type\":\"string\"}," +
           "\"ord\":{\"type\":\"string\"}," +
           "\"kind\":{\"type\":\"string\"}," +
           "\"role_in_space\":{\"type\":\"string\"}}," +
           "\"required\":[\"id\",\"name\",\"ord\"]}";
  }

  @Override
  public String call(JSONObject args) throws Exception {
    String id = args.getString("id");
    KnowledgeStore ks = BMcpPlatformService.getKnowledgeStore();
    if (ks == null) throw new IllegalStateException("Knowledge store not available");
    KnowledgeModel km = ks.getModel();
    if (km.getStandalonePoint(id) != null)
      throw new IllegalArgumentException("Point already exists: " + id);
    String space = args.optString("space", null);
    if (space != null && !space.isEmpty() && km.getSpace(space) == null)
      throw new IllegalArgumentException("Unknown space: " + space);

    StandalonePoint p = new StandalonePoint();
    p.id   = id;
    p.name = args.getString("name");
    p.ord  = args.getString("ord");
    p.space       = (space != null && space.isEmpty()) ? null : space;
    p.kind        = args.optString("kind", null);
    p.roleInSpace = args.optString("role_in_space", null);
    JSONArray al = args.optJSONArray("aliases");
    if (al != null) for (int i = 0; i < al.length(); i++) p.aliases.add(al.getString(i));

    km.standalonePoints.add(p);
    ks.save("createStandalonePoint", id, null);

    JSONObject r = new JSONObject();
    r.put("ok", true);
    r.put("id", id);
    return r.toString();
  }
}
