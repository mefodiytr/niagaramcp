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

import java.util.List;

/** Run schema validation; report warnings without modifying state. */
public final class ValidateKnowledgeTool implements Tool {

  @Override public String name()        { return "validateKnowledge"; }
  @Override public String getCategory() { return "walkthrough-write"; }
  @Override public String description() {
    return "Validate the current knowledge model against schema rules. Returns a list of " +
           "warnings (orphan refs, duplicate ids, parent cycles). Read-only.";
  }
  @Override public String schemaJson() {
    return ToolSchemaHelpers.emptySchema();
  }

  @Override
  public String call(JSONObject args) throws Exception {
    KnowledgeStore ks = BMcpPlatformService.getKnowledgeStore();
    if (ks == null) throw new IllegalStateException("Knowledge store not available");
    KnowledgeModel km = ks.getModel();
    List<String> warnings = km.validate();

    JSONArray arr = new JSONArray();
    for (String w : warnings) arr.put(w);
    JSONObject r = new JSONObject();
    r.put("ok", warnings.isEmpty());
    r.put("warningCount", warnings.size());
    r.put("warnings", arr);
    return r.toString();
  }
}
