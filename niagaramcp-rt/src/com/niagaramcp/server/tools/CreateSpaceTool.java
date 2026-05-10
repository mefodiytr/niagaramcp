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
import com.niagaramcp.server.knowledge.Space;

/** Create a new {@link Space} in the knowledge model. */
public final class CreateSpaceTool implements Tool {

  @Override public String name()        { return "createSpace"; }
  @Override public String getCategory() { return "walkthrough-write"; }
  @Override public String description() {
    return "Create a new space (zone/floor/parking/...) in the knowledge model. " +
           "id must be unique and kebab-case.";
  }
  @Override public String schemaJson() {
    return "{\"type\":\"object\",\"properties\":{" +
           "\"id\":{\"type\":\"string\"}," +
           "\"name\":{\"type\":\"string\"}," +
           "\"aliases\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}," +
           "\"type\":{\"type\":\"string\"}," +
           "\"description\":{\"type\":\"string\"}," +
           "\"parent\":{\"type\":\"string\"}}," +
           "\"required\":[\"id\",\"name\"]}";
  }

  @Override
  public String call(JSONObject args) throws Exception {
    String id   = args.getString("id");
    String name = args.getString("name");
    KnowledgeStore ks = BMcpPlatformService.getKnowledgeStore();
    if (ks == null) throw new IllegalStateException("Knowledge store not available");

    KnowledgeModel km = ks.getModel();
    if (km.getSpace(id) != null) {
      throw new IllegalArgumentException("Space already exists: " + id);
    }
    String parent = args.optString("parent", null);
    if (parent != null && !parent.isEmpty() && km.getSpace(parent) == null) {
      throw new IllegalArgumentException("Unknown parent space: " + parent);
    }

    Space s = new Space(id, name);
    s.type        = args.optString("type", null);
    s.description = args.optString("description", null);
    s.parent      = (parent != null && parent.isEmpty()) ? null : parent;

    JSONArray al = args.optJSONArray("aliases");
    if (al != null) for (int i = 0; i < al.length(); i++) s.aliases.add(al.getString(i));

    km.spaces.add(s);
    ks.save("createSpace", id, null);

    JSONObject r = new JSONObject();
    r.put("ok", true);
    r.put("id", id);
    return r.toString();
  }
}
