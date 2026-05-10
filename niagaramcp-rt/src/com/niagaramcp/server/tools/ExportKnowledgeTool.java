/*
 * Copyright 2026 niagaramcp contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.niagaramcp.server.tools;

import com.niagaramcp.json.JSONObject;
import com.niagaramcp.server.BMcpPlatformService;
import com.niagaramcp.server.knowledge.KnowledgeStore;
import com.niagaramcp.server.yaml.YamlWriter;

/** Export the current model as a string in YAML or JSON. */
public final class ExportKnowledgeTool implements Tool {

  @Override public String name()        { return "exportKnowledge"; }
  @Override public String getCategory() { return "management"; }
  @Override public String description() {
    return "Return the entire knowledge model as a string. format: 'yaml' (default) or 'json'.";
  }
  @Override public String schemaJson() {
    return "{\"type\":\"object\",\"properties\":{" +
           "\"format\":{\"type\":\"string\",\"enum\":[\"yaml\",\"json\"]}}}";
  }

  @Override
  public String call(JSONObject args) throws Exception {
    String format = args.optString("format", "yaml").toLowerCase(java.util.Locale.ROOT);
    KnowledgeStore ks = BMcpPlatformService.getKnowledgeStore();
    if (ks == null) throw new IllegalStateException("Knowledge store not available");
    Object tree = ks.getModel().toTree();
    String text = "json".equals(format)
        ? new JSONObject((java.util.Map<?, ?>) tree).toString(2)
        : YamlWriter.write(tree);

    JSONObject out = new JSONObject();
    out.put("format", format);
    out.put("size", text.length());
    out.put("content", text);
    return out.toString();
  }
}
