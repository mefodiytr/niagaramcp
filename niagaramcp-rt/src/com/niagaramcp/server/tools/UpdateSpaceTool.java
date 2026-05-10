/*
 * Copyright 2026 niagaramcp contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.niagaramcp.server.tools;

import com.niagaramcp.json.JSONArray;
import com.niagaramcp.json.JSONObject;
import com.niagaramcp.server.BMcpPlatformService;
import com.niagaramcp.server.knowledge.KnowledgeStore;
import com.niagaramcp.server.knowledge.Space;

/** Update an existing {@link Space} — only fields present in args are touched. */
public final class UpdateSpaceTool implements Tool {

  @Override public String name()        { return "updateSpace"; }
  @Override public String description() {
    return "Update fields of an existing space. Only fields present in args are modified; " +
           "absent fields preserve existing values. id is required for lookup.";
  }
  @Override public String schemaJson() {
    return "{\"type\":\"object\",\"properties\":{" +
           "\"id\":{\"type\":\"string\"}," +
           "\"name\":{\"type\":\"string\"}," +
           "\"aliases\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}," +
           "\"type\":{\"type\":\"string\"}," +
           "\"description\":{\"type\":\"string\"}," +
           "\"parent\":{\"type\":\"string\"}}," +
           "\"required\":[\"id\"]}";
  }

  @Override
  public String call(JSONObject args) throws Exception {
    String id = args.getString("id");
    KnowledgeStore ks = BMcpPlatformService.getKnowledgeStore();
    if (ks == null) throw new IllegalStateException("Knowledge store not available");
    Space s = ks.getModel().getSpace(id);
    if (s == null) throw new IllegalArgumentException("Unknown space: " + id);

    if (args.has("name"))        s.name        = args.optString("name", s.name);
    if (args.has("type"))        s.type        = args.optString("type", s.type);
    if (args.has("description")) s.description = args.optString("description", s.description);
    if (args.has("parent"))      s.parent      = args.optString("parent", s.parent);
    if (args.has("aliases")) {
      JSONArray al = args.getJSONArray("aliases");
      s.aliases.clear();
      for (int i = 0; i < al.length(); i++) s.aliases.add(al.getString(i));
    }
    ks.save("updateSpace", id, null);

    JSONObject r = new JSONObject();
    r.put("ok", true);
    r.put("id", id);
    return r.toString();
  }
}
