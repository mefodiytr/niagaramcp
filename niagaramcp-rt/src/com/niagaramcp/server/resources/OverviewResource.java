/*
 * Copyright 2026 niagaramcp contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.niagaramcp.server.resources;

import com.niagaramcp.json.JSONObject;
import com.niagaramcp.server.BMcpPlatformService;
import com.niagaramcp.server.Resource;
import com.niagaramcp.server.knowledge.KnowledgeModel;
import com.niagaramcp.server.knowledge.KnowledgeStore;

import java.util.LinkedHashMap;
import java.util.Map;

/** {@code niagara://overview} — top-level station summary derived from the knowledge model. */
public final class OverviewResource implements Resource {

  public static final String URI = "niagara://overview";

  @Override public String uri()         { return URI; }
  @Override public String uriTemplate() { return null; }
  @Override public String name()        { return "Station overview"; }
  @Override public String description() { return "Top-level station identity + counts of spaces/equipment_types/equipment/standalonePoints"; }
  @Override public String mimeType()    { return "application/json"; }
  @Override public boolean matches(String u) { return URI.equals(u); }

  @Override
  public String read(String uri) {
    KnowledgeStore ks = BMcpPlatformService.getKnowledgeStore();
    JSONObject o = new JSONObject();
    if (ks == null) {
      o.put("status", "knowledge store not available");
      return o.toString();
    }
    KnowledgeModel km = ks.getModel();
    Map<String,Object> station = new LinkedHashMap<String,Object>(km.station);
    o.put("station", station);
    o.put("schemaVersion", km.schemaVersion);
    o.put("spaceCount",          km.spaces.size());
    o.put("equipmentTypeCount",  km.equipmentTypes.size());
    o.put("equipmentCount",      km.equipment.size());
    o.put("standalonePointCount", km.standalonePoints.size());
    return o.toString();
  }
}
