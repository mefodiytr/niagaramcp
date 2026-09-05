/*
 * Copyright 2026 niagaramcp contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.niagaramcp.server.tools;

import com.niagaramcp.json.JSONObject;
import com.niagaramcp.server.BMcpPlatformService;
import com.niagaramcp.server.knowledge.KnowledgeStore;

/** Reload the knowledge file from disk after manual operator edits. */
public final class ReloadKnowledgeTool implements Tool {

  @Override public String name()        { return "reloadKnowledge"; }
  @Override public String getCategory() { return "management"; }
  @Override public String description() {
    return "Re-read the knowledge file from disk. Use after manually editing the file in " +
           "an external editor. No-op if the in-memory model is already current.";
  }
  @Override public String schemaJson() { return ToolSchemaHelpers.emptySchema(); }

  @Override
  public String call(JSONObject args) throws Exception {
    KnowledgeStore ks = BMcpPlatformService.getKnowledgeStore();
    if (ks == null) throw new IllegalStateException("Knowledge store not available");
    ks.reload();
    JSONObject r = new JSONObject();
    r.put("ok", true);
    r.put("filePath", ks.describeLocation());
    r.put("equipmentCount", ks.getModel().equipment.size());
    return r.toString();
  }
}
