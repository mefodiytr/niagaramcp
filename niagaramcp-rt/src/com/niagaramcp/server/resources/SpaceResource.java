/*
 * Copyright 2026 niagaramcp contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.niagaramcp.server.resources;

import com.niagaramcp.json.JSONArray;
import com.niagaramcp.json.JSONObject;
import com.niagaramcp.server.BMcpPlatformService;
import com.niagaramcp.server.Resource;
import com.niagaramcp.server.knowledge.Equipment;
import com.niagaramcp.server.knowledge.KnowledgeStore;
import com.niagaramcp.server.knowledge.Space;
import com.niagaramcp.server.knowledge.StandalonePoint;

/** {@code niagara://spaces/{id}} — space record + equipment/points contained in it. */
public final class SpaceResource implements Resource {

  public static final String PREFIX   = "niagara://spaces/";
  public static final String TEMPLATE = "niagara://spaces/{id}";

  @Override public String uri()         { return null; }
  @Override public String uriTemplate() { return TEMPLATE; }
  @Override public String name()        { return "Space by id"; }
  @Override public String description() { return "Space record plus equipment and standalone points within it"; }
  @Override public String mimeType()    { return "application/json"; }
  @Override public boolean matches(String u) { return u != null && u.startsWith(PREFIX); }

  @Override
  public String read(String uri) {
    String id = uri.substring(PREFIX.length());
    KnowledgeStore ks = BMcpPlatformService.getKnowledgeStore();
    if (ks == null) throw new IllegalStateException("Knowledge store not available");
    Space s = ks.getModel().getSpace(id);
    if (s == null) {
      JSONObject err = new JSONObject();
      err.put("error", "Unknown space: " + id);
      return err.toString();
    }
    JSONObject out = new JSONObject(s.toMap());
    JSONArray eqArr = new JSONArray();
    for (Equipment e : ks.getModel().equipment) {
      if (id.equals(e.space)) {
        JSONObject eo = new JSONObject();
        eo.put("id", e.id);
        eo.put("name", e.name == null ? "" : e.name);
        eo.put("type", e.type == null ? "" : e.type);
        eo.put("ord", e.ord == null ? "" : e.ord);
        eqArr.put(eo);
      }
    }
    JSONArray pArr = new JSONArray();
    for (StandalonePoint p : ks.getModel().standalonePoints) {
      if (id.equals(p.space)) {
        JSONObject po = new JSONObject();
        po.put("id", p.id);
        po.put("name", p.name == null ? "" : p.name);
        po.put("kind", p.kind == null ? "" : p.kind);
        po.put("ord", p.ord == null ? "" : p.ord);
        pArr.put(po);
      }
    }
    out.put("equipment", eqArr);
    out.put("standalonePoints", pArr);
    return out.toString();
  }
}
