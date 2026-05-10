/*
 * Copyright 2026 niagaramcp contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.niagaramcp.server.tools;

import com.niagaramcp.json.JSONObject;
import com.niagaramcp.server.BMcpPlatformService;
import com.niagaramcp.server.knowledge.KnowledgeModel;
import com.niagaramcp.server.knowledge.KnowledgeStore;

/** Counts and high-level stats of the knowledge model. */
public final class GetKnowledgeSummaryTool implements Tool {

  @Override public String name()        { return "getKnowledgeSummary"; }
  @Override public String getCategory() { return "management"; }
  @Override public String description() {
    return "Counts of spaces / equipment_types / equipment / standalone points; current " +
           "schema_version and storage format (yaml|json).";
  }
  @Override public String schemaJson() { return "{\"type\":\"object\",\"properties\":{}}"; }

  @Override
  public String call(JSONObject args) throws Exception {
    KnowledgeStore ks = BMcpPlatformService.getKnowledgeStore();
    if (ks == null) throw new IllegalStateException("Knowledge store not available");
    KnowledgeModel km = ks.getModel();

    JSONObject out = new JSONObject();
    out.put("schemaVersion", km.schemaVersion);
    out.put("format", ks.getFormat().name().toLowerCase(java.util.Locale.ROOT));
    out.put("filePath", ks.getFile() == null ? "" : ks.getFile().getAbsolutePath());
    out.put("spaceCount",          km.spaces.size());
    out.put("equipmentTypeCount",  km.equipmentTypes.size());
    out.put("equipmentCount",      km.equipment.size());
    out.put("standalonePointCount", km.standalonePoints.size());
    return out.toString();
  }
}
