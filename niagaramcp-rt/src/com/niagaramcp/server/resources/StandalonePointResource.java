/*
 * Copyright 2026 niagaramcp contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.niagaramcp.server.resources;

import com.niagaramcp.json.JSONObject;
import com.niagaramcp.server.BMcpPlatformService;
import com.niagaramcp.server.Resource;
import com.niagaramcp.server.knowledge.KnowledgeStore;
import com.niagaramcp.server.knowledge.StandalonePoint;

/** {@code niagara://standalone-points/{id}} — single stand-alone sensor record. */
public final class StandalonePointResource implements Resource {

  public static final String PREFIX   = "niagara://standalone-points/";
  public static final String TEMPLATE = "niagara://standalone-points/{id}";

  @Override public String uri()         { return null; }
  @Override public String uriTemplate() { return TEMPLATE; }
  @Override public String name()        { return "Stand-alone point by id"; }
  @Override public String description() { return "Stand-alone sensor record (CO2/ambient temp/etc., not bound to specific equipment)"; }
  @Override public String mimeType()    { return "application/json"; }
  @Override public boolean matches(String u) { return u != null && u.startsWith(PREFIX); }

  @Override
  public String read(String uri) {
    String id = uri.substring(PREFIX.length());
    KnowledgeStore ks = BMcpPlatformService.getKnowledgeStore();
    if (ks == null) throw new IllegalStateException("Knowledge store not available");
    StandalonePoint p = ks.getModel().getStandalonePoint(id);
    if (p == null) {
      JSONObject err = new JSONObject();
      err.put("error", "Unknown stand-alone point: " + id);
      return err.toString();
    }
    return new JSONObject(p.toMap()).toString();
  }
}
