/*
 * Copyright 2026 niagaramcp contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.niagaramcp.server.tools;

import com.niagaramcp.json.JSONArray;
import com.niagaramcp.json.JSONObject;
import com.niagaramcp.server.BMcpPlatformService;
import com.niagaramcp.server.McpSessions;
import com.niagaramcp.server.Prompt;
import com.niagaramcp.server.PromptRegistry;
import com.niagaramcp.server.Resource;
import com.niagaramcp.server.ResourceRegistry;
import com.niagaramcp.server.ToolRegistry;
import com.niagaramcp.server.knowledge.KnowledgeStore;

import java.io.File;

/** Diagnostic snapshot: version, uptime, sessions, knowledge file, registries. */
public final class GetServerInfoTool implements Tool {

  /** Module version string. Read by health endpoint and serverInfo.version. */
  public static final String NIAGARAMCP_VERSION = "0.4.1";

  @Override public String name()        { return "getServerInfo"; }
  @Override public String getCategory() { return "diagnostic"; }
  @Override public String description() {
    return "Server diagnostic snapshot — version, uptimeSeconds, sessionCount, " +
           "knowledgeFile {path,size,equipmentCount,lastModifiedMs}, transports, " +
           "registered tools/resources/prompts.";
  }
  @Override public String schemaJson() { return ToolSchemaHelpers.emptySchema(); }

  @Override
  public String call(JSONObject args) throws Exception {
    JSONObject out = new JSONObject();
    out.put("version", NIAGARAMCP_VERSION);

    long startMs = BMcpPlatformService.getServiceStartTimeMs();
    long uptime = startMs == 0 ? 0 : (System.currentTimeMillis() - startMs) / 1000L;
    out.put("uptimeSeconds", uptime);

    out.put("sessionCount", McpSessions.activeCount());

    // Knowledge file
    JSONObject kf = new JSONObject();
    KnowledgeStore ks = BMcpPlatformService.getKnowledgeStore();
    if (ks != null && ks.getFile() != null) {
      File f = ks.getFile();
      kf.put("path", f.getAbsolutePath());
      kf.put("size", f.exists() ? f.length() : 0);
      kf.put("equipmentCount", ks.getModel().equipment.size());
      kf.put("lastModifiedMs", f.exists() ? f.lastModified() : 0);
      kf.put("exists", f.exists());
    }
    out.put("knowledgeFile", kf);

    // Transports — both supported in v0.2+
    JSONArray transports = new JSONArray();
    transports.put("sse");
    transports.put("streamable-http");
    out.put("transports", transports);

    // Tools
    JSONArray toolNames = new JSONArray();
    ToolRegistry tr = BMcpPlatformService.getRegistry();
    if (tr != null) for (Tool t : tr.all()) toolNames.put(t.name());
    out.put("tools", toolNames);

    // Resources (URIs of static + URI templates)
    JSONArray resourceUris = new JSONArray();
    ResourceRegistry rr = BMcpPlatformService.getResourceRegistry();
    if (rr != null) {
      for (Resource r : rr.all()) {
        resourceUris.put(r.uri() != null ? r.uri() : r.uriTemplate());
      }
    }
    out.put("resources", resourceUris);

    // Prompts
    JSONArray promptNames = new JSONArray();
    PromptRegistry pr = BMcpPlatformService.getPromptRegistry();
    if (pr != null) for (Prompt p : pr.all()) promptNames.put(p.name());
    out.put("prompts", promptNames);

    return out.toString();
  }
}
