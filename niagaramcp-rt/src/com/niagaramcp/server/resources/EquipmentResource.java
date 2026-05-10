/*
 * Copyright 2026 niagaramcp contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.niagaramcp.server.resources;

import com.niagaramcp.json.JSONObject;
import com.niagaramcp.server.BMcpPlatformService;
import com.niagaramcp.server.Resource;
import com.niagaramcp.server.knowledge.Equipment;
import com.niagaramcp.server.knowledge.KnowledgeStore;

/** {@code niagara://equipment/{id}} — single equipment record. */
public final class EquipmentResource implements Resource {

  public static final String PREFIX   = "niagara://equipment/";
  public static final String TEMPLATE = "niagara://equipment/{id}";

  @Override public String uri()         { return null; }
  @Override public String uriTemplate() { return TEMPLATE; }
  @Override public String name()        { return "Equipment by id"; }
  @Override public String description() { return "Full record of a single equipment by id"; }
  @Override public String mimeType()    { return "application/json"; }
  @Override public boolean matches(String u) { return u != null && u.startsWith(PREFIX); }

  @Override
  public String read(String uri) {
    String id = uri.substring(PREFIX.length());
    KnowledgeStore ks = BMcpPlatformService.getKnowledgeStore();
    if (ks == null) throw new IllegalStateException("Knowledge store not available");
    Equipment e = ks.getModel().getEquipment(id);
    if (e == null) {
      JSONObject err = new JSONObject();
      err.put("error", "Unknown equipment: " + id);
      return err.toString();
    }
    return new JSONObject(e.toMap()).toString();
  }
}
