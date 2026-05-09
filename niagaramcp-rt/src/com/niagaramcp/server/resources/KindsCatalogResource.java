/*
 * Copyright 2026 niagaramcp contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.niagaramcp.server.resources;

import com.niagaramcp.json.JSONArray;
import com.niagaramcp.json.JSONObject;
import com.niagaramcp.server.BMcpPlatformService;
import com.niagaramcp.server.Resource;
import com.niagaramcp.server.knowledge.EquipmentType;
import com.niagaramcp.server.knowledge.KnowledgeStore;

/** {@code niagara://kinds/catalog} — equipment_types section of the knowledge model. */
public final class KindsCatalogResource implements Resource {

  public static final String URI = "niagara://kinds/catalog";

  @Override public String uri()         { return URI; }
  @Override public String uriTemplate() { return null; }
  @Override public String name()        { return "Equipment kinds catalog"; }
  @Override public String description() { return "All equipment_types defined in the knowledge model"; }
  @Override public String mimeType()    { return "application/json"; }
  @Override public boolean matches(String u) { return URI.equals(u); }

  @Override
  public String read(String uri) {
    KnowledgeStore ks = BMcpPlatformService.getKnowledgeStore();
    JSONArray arr = new JSONArray();
    if (ks != null) {
      for (EquipmentType e : ks.getModel().equipmentTypes) {
        arr.put(new JSONObject(e.toMap()));
      }
    }
    JSONObject out = new JSONObject();
    out.put("equipment_types", arr);
    out.put("count", arr.length());
    return out.toString();
  }
}
