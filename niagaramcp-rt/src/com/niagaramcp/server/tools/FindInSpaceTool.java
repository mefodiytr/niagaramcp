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
import com.niagaramcp.server.knowledge.Space;
import com.niagaramcp.server.knowledge.StandalonePoint;

import java.util.HashSet;
import java.util.Set;

/** List equipment + standalone points whose space is the given id (or its descendants). */
public final class FindInSpaceTool implements Tool {

  @Override public String name()        { return "findInSpace"; }
  @Override public String description() {
    return "List equipment and stand-alone points within a space. If recursive=true, " +
           "includes descendant spaces. equipmentType is an optional type filter.";
  }
  @Override public String schemaJson() {
    return "{\"type\":\"object\",\"properties\":{" +
           "\"spaceId\":{\"type\":\"string\"}," +
           "\"recursive\":{\"type\":\"boolean\"}," +
           "\"equipmentType\":{\"type\":\"string\"}}," +
           "\"required\":[\"spaceId\"]}";
  }

  @Override
  public String call(JSONObject args) throws Exception {
    String spaceId = args.getString("spaceId");
    boolean recursive = args.optBoolean("recursive", false);
    String typeFilter = args.optString("equipmentType", null);

    KnowledgeStore ks = BMcpPlatformService.getKnowledgeStore();
    if (ks == null) throw new IllegalStateException("Knowledge store not available");
    KnowledgeModel km = ks.getModel();
    if (km.getSpace(spaceId) == null) throw new IllegalArgumentException("Unknown space: " + spaceId);

    Set<String> spaces = new HashSet<String>();
    spaces.add(spaceId);
    if (recursive) {
      // BFS over parent links, gather descendants
      boolean changed = true;
      while (changed) {
        changed = false;
        for (Space s : km.spaces) {
          if (s.parent != null && spaces.contains(s.parent) && !spaces.contains(s.id)) {
            spaces.add(s.id);
            changed = true;
          }
        }
      }
    }

    JSONArray equipArr = new JSONArray();
    for (Equipment e : km.equipment) {
      if (e.space == null || !spaces.contains(e.space)) continue;
      if (typeFilter != null && !typeFilter.isEmpty() && !typeFilter.equals(e.type)) continue;
      JSONObject o = new JSONObject();
      o.put("id", e.id);
      o.put("name", e.name == null ? "" : e.name);
      o.put("type", e.type == null ? "" : e.type);
      o.put("space", e.space);
      o.put("ord", e.ord == null ? "" : e.ord);
      equipArr.put(o);
    }
    JSONArray pointArr = new JSONArray();
    for (StandalonePoint p : km.standalonePoints) {
      if (p.space == null || !spaces.contains(p.space)) continue;
      JSONObject o = new JSONObject();
      o.put("id", p.id);
      o.put("name", p.name == null ? "" : p.name);
      o.put("kind", p.kind == null ? "" : p.kind);
      o.put("space", p.space);
      o.put("ord", p.ord == null ? "" : p.ord);
      pointArr.put(o);
    }

    JSONObject out = new JSONObject();
    out.put("spaceId", spaceId);
    out.put("recursive", recursive);
    out.put("spacesIncluded", spaces.size());
    out.put("equipment", equipArr);
    out.put("standalonePoints", pointArr);
    return out.toString();
  }
}
