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
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Static feature inventory of the running niagaramcp instance — version,
 * categorised tool list, resources, prompts, transports, knowledge stats,
 * session count, health, and the JSON-RPC impl-defined error codes.
 *
 * <p>Designed for AI clients on discovery and operators copy-pasting from
 * MCP responses. Two output formats: human-readable {@code "text"} (default)
 * and machine-readable {@code "json"}.
 *
 * <p>Scope: <em>static</em> feature inventory only — no log analysis,
 * no alarm dumps. For the dynamic snapshot use {@code getDiagnosticDump}.
 */
public final class GetFeatureDumpTool implements Tool {

  /** Display order of tool categories — matches the v0.4 category tags. */
  private static final String[] CATEGORY_ORDER = new String[] {
    "transport-test", "read", "write", "walkthrough-read", "walkthrough-write",
    "management", "search", "history", "alarms", "diagnostic", "general"
  };

  /**
   * JSON-RPC impl-defined error codes (-32001..-32009) — fixed by spec,
   * enumerated here so clients can self-document without separate docs.
   */
  private static final String[][] ERROR_CODES = new String[][] {
    {"-32001", "session not found"},
    {"-32002", "tool not found"},
    {"-32003", "resource not found"},
    {"-32004", "knowledge file unreadable"},
    {"-32005", "schema validation error"},
    {"-32006", "ord not resolvable"},
    {"-32007", "history extension not present"},
    {"-32008", "alarm service not available"},
    {"-32009", "transport disabled"}
  };

  @Override public String name()        { return "getFeatureDump"; }
  @Override public String getCategory() { return "diagnostic"; }
  @Override public String description() {
    return "Static feature inventory of the running server (version, " +
           "tools by category, resources, prompts, transports, knowledge " +
           "stats, session count, health, JSON-RPC error codes). " +
           "Args: format=\"text\"|\"json\" (default text).";
  }
  @Override public String schemaJson() {
    JSONObject fmt = new JSONObject();
    fmt.put("type", "string");
    fmt.put("enum", new JSONArray().put("text").put("json"));
    fmt.put("description", "Output format: text (default) or json.");
    return ToolSchemaHelpers.objectSchema(new String[0], "format", fmt);
  }

  @Override
  public String call(JSONObject args) throws Exception {
    String format = (args != null && args.has("format")) ? args.getString("format") : "text";
    if (!"json".equals(format) && !"text".equals(format)) format = "text";

    Snapshot snap = collect();
    return "json".equals(format) ? renderJson(snap) : renderText(snap);
  }

  // ---------- collection ----------

  /** Frozen view of the server's static features at the moment of the call. */
  private static final class Snapshot {
    String version;
    Map<String, List<String>> toolsByCategory = new LinkedHashMap<String, List<String>>();
    int toolCount;
    List<String> staticResourceUris   = new ArrayList<String>();
    List<String> templateResourceUris = new ArrayList<String>();
    List<String> promptNames          = new ArrayList<String>();
    boolean sseEnabled;
    boolean streamableEnabled;
    String  knowledgeFilePath;
    long    knowledgeFileSize;
    boolean knowledgeFileExists;
    int     equipmentCount;
    int     spaceCount;
    int     equipmentTypeCount;
    int     standalonePointCount;
    int     sessionCount;
    String  alarmServiceHealth;
    String  historyServiceHealth;
    String  knowledgeFileHealth;
  }

  private static Snapshot collect() {
    Snapshot s = new Snapshot();
    s.version = GetServerInfoTool.NIAGARAMCP_VERSION;

    // Tools, grouped by category
    ToolRegistry tr = BMcpPlatformService.getRegistry();
    if (tr != null) {
      for (Tool t : tr.all()) {
        String cat = t.getCategory();
        if (cat == null || cat.isEmpty()) cat = "general";
        List<String> bucket = s.toolsByCategory.get(cat);
        if (bucket == null) {
          bucket = new ArrayList<String>();
          s.toolsByCategory.put(cat, bucket);
        }
        bucket.add(t.name());
        s.toolCount++;
      }
    }

    // Resources, split static vs templated
    ResourceRegistry rr = BMcpPlatformService.getResourceRegistry();
    if (rr != null) {
      for (Resource r : rr.all()) {
        if (r.uri() != null) s.staticResourceUris.add(r.uri());
        else if (r.uriTemplate() != null) s.templateResourceUris.add(r.uriTemplate());
      }
    }

    // Prompts
    PromptRegistry pr = BMcpPlatformService.getPromptRegistry();
    if (pr != null) for (Prompt p : pr.all()) s.promptNames.add(p.name());

    s.sseEnabled        = BMcpPlatformService.sseEnabled();
    s.streamableEnabled = BMcpPlatformService.streamableEnabled();
    s.sessionCount      = McpSessions.activeCount();

    // Knowledge
    KnowledgeStore ks = BMcpPlatformService.getKnowledgeStore();
    if (ks != null) {
      File f = ks.getFile();
      if (f != null) {
        s.knowledgeFilePath   = f.getAbsolutePath();
        s.knowledgeFileExists = f.exists();
        s.knowledgeFileSize   = s.knowledgeFileExists ? f.length() : 0L;
      }
      s.equipmentCount       = ks.getModel().equipment.size();
      s.spaceCount           = ks.getModel().spaces.size();
      s.equipmentTypeCount   = ks.getModel().equipmentTypes.size();
      s.standalonePointCount = ks.getModel().standalonePoints.size();
    }

    s.alarmServiceHealth   = svcStatus(javax.baja.alarm.BAlarmService.TYPE);
    s.historyServiceHealth = svcStatus(javax.baja.history.BHistoryService.TYPE);
    if (ks != null && ks.getFile() != null) {
      File f = ks.getFile();
      s.knowledgeFileHealth = (f.exists() && f.canRead()) ? "ok"
                                                          : (f.exists() ? "unreadable" : "missing");
    } else {
      s.knowledgeFileHealth = "no-store";
    }

    return s;
  }

  // ---------- text rendering ----------

  private static String renderText(Snapshot s) {
    StringBuilder b = new StringBuilder(2048);
    b.append("=== niagaramcp v").append(s.version).append(" Feature Dump ===\n\n");

    b.append("Tools (").append(s.toolCount).append("):\n");
    int colWidth = longestCategoryLength(s.toolsByCategory.keySet()) + 2;
    for (int i = 0; i < CATEGORY_ORDER.length; i++) {
      String cat = CATEGORY_ORDER[i];
      List<String> names = s.toolsByCategory.get(cat);
      if (names == null || names.isEmpty()) continue;
      b.append("  [").append(cat).append("]");
      pad(b, colWidth - cat.length());
      b.append(joinCommas(names)).append('\n');
    }
    // any unknown categories that didn't match the predefined order
    for (Iterator<Map.Entry<String, List<String>>> it = s.toolsByCategory.entrySet().iterator(); it.hasNext(); ) {
      Map.Entry<String, List<String>> e = it.next();
      if (containsCategory(CATEGORY_ORDER, e.getKey())) continue;
      b.append("  [").append(e.getKey()).append("]");
      pad(b, colWidth - e.getKey().length());
      b.append(joinCommas(e.getValue())).append('\n');
    }

    int resCount = s.staticResourceUris.size() + s.templateResourceUris.size();
    b.append("\nResources (").append(resCount).append("):\n");
    if (!s.staticResourceUris.isEmpty()) {
      b.append("  Static:\n");
      for (String u : s.staticResourceUris) b.append("    ").append(u).append('\n');
    }
    if (!s.templateResourceUris.isEmpty()) {
      b.append("  Templates:\n");
      for (String u : s.templateResourceUris) b.append("    ").append(u).append('\n');
    }

    b.append("\nPrompts (").append(s.promptNames.size()).append("):\n  ");
    b.append(joinCommas(s.promptNames)).append('\n');

    b.append("\nTransports:\n");
    b.append("  sse:             ").append(s.sseEnabled ? "enabled" : "disabled").append('\n');
    b.append("  streamable-http: ").append(s.streamableEnabled ? "enabled" : "disabled").append('\n');

    b.append("\nKnowledge:\n");
    b.append("  filePath:           ").append(s.knowledgeFilePath == null ? "(unset)" : s.knowledgeFilePath).append('\n');
    b.append("  fileExists:         ").append(s.knowledgeFileExists).append('\n');
    b.append("  fileSize:           ").append(s.knowledgeFileSize).append('\n');
    b.append("  equipmentCount:     ").append(s.equipmentCount).append('\n');
    b.append("  spaceCount:         ").append(s.spaceCount).append('\n');
    b.append("  equipmentTypeCount: ").append(s.equipmentTypeCount).append('\n');
    b.append("  standalonePointCount: ").append(s.standalonePointCount).append('\n');

    b.append("\nSessions: ").append(s.sessionCount).append(" active\n");

    b.append("\nHealth:\n");
    b.append("  alarmService:   ").append(s.alarmServiceHealth).append('\n');
    b.append("  historyService: ").append(s.historyServiceHealth).append('\n');
    b.append("  knowledgeFile:  ").append(s.knowledgeFileHealth).append('\n');

    b.append("\nJSON-RPC error codes (impl-defined):\n");
    for (int i = 0; i < ERROR_CODES.length; i++) {
      b.append("  ").append(ERROR_CODES[i][0]).append(' ').append(ERROR_CODES[i][1]).append('\n');
    }
    b.append("\n===\n");
    return b.toString();
  }

  // ---------- json rendering ----------

  private static String renderJson(Snapshot s) {
    JSONObject root = new JSONObject();
    root.put("version", s.version);

    JSONObject tools = new JSONObject();
    tools.put("totalCount", s.toolCount);
    JSONObject byCat = new JSONObject();
    for (int i = 0; i < CATEGORY_ORDER.length; i++) {
      String cat = CATEGORY_ORDER[i];
      List<String> names = s.toolsByCategory.get(cat);
      if (names == null || names.isEmpty()) continue;
      byCat.put(cat, toJsonArray(names));
    }
    for (Iterator<Map.Entry<String, List<String>>> it = s.toolsByCategory.entrySet().iterator(); it.hasNext(); ) {
      Map.Entry<String, List<String>> e = it.next();
      if (containsCategory(CATEGORY_ORDER, e.getKey())) continue;
      byCat.put(e.getKey(), toJsonArray(e.getValue()));
    }
    tools.put("byCategory", byCat);
    root.put("tools", tools);

    JSONObject resources = new JSONObject();
    resources.put("totalCount", s.staticResourceUris.size() + s.templateResourceUris.size());
    resources.put("static",    toJsonArray(s.staticResourceUris));
    resources.put("templates", toJsonArray(s.templateResourceUris));
    root.put("resources", resources);

    JSONObject prompts = new JSONObject();
    prompts.put("totalCount", s.promptNames.size());
    prompts.put("names", toJsonArray(s.promptNames));
    root.put("prompts", prompts);

    JSONObject transports = new JSONObject();
    transports.put("sse",             s.sseEnabled);
    transports.put("streamableHttp",  s.streamableEnabled);
    root.put("transports", transports);

    JSONObject knowledge = new JSONObject();
    knowledge.put("filePath",             s.knowledgeFilePath == null ? "" : s.knowledgeFilePath);
    knowledge.put("fileExists",           s.knowledgeFileExists);
    knowledge.put("fileSize",             s.knowledgeFileSize);
    knowledge.put("equipmentCount",       s.equipmentCount);
    knowledge.put("spaceCount",           s.spaceCount);
    knowledge.put("equipmentTypeCount",   s.equipmentTypeCount);
    knowledge.put("standalonePointCount", s.standalonePointCount);
    root.put("knowledge", knowledge);

    root.put("sessionCount", s.sessionCount);

    JSONObject health = new JSONObject();
    health.put("alarmService",   s.alarmServiceHealth);
    health.put("historyService", s.historyServiceHealth);
    health.put("knowledgeFile",  s.knowledgeFileHealth);
    root.put("health", health);

    JSONArray errs = new JSONArray();
    for (int i = 0; i < ERROR_CODES.length; i++) {
      JSONObject ec = new JSONObject();
      ec.put("code",    Integer.parseInt(ERROR_CODES[i][0]));
      ec.put("meaning", ERROR_CODES[i][1]);
      errs.put(ec);
    }
    root.put("errorCodes", errs);

    return root.toString();
  }

  // ---------- helpers ----------

  private static String svcStatus(javax.baja.sys.Type type) {
    try {
      Object svc = javax.baja.sys.Sys.getService(type);
      return svc == null ? "unavailable" : "ok";
    } catch (Exception e) {
      return "error";
    }
  }

  private static int longestCategoryLength(java.util.Set<String> cats) {
    int max = 0;
    for (String c : cats) if (c != null && c.length() > max) max = c.length();
    return max;
  }

  private static void pad(StringBuilder b, int n) {
    for (int i = 0; i < n; i++) b.append(' ');
  }

  private static String joinCommas(List<String> list) {
    if (list.isEmpty()) return "";
    StringBuilder b = new StringBuilder(list.size() * 16);
    for (int i = 0; i < list.size(); i++) {
      if (i > 0) b.append(", ");
      b.append(list.get(i));
    }
    return b.toString();
  }

  private static boolean containsCategory(String[] arr, String c) {
    for (int i = 0; i < arr.length; i++) if (arr[i].equals(c)) return true;
    return false;
  }

  private static JSONArray toJsonArray(List<String> list) {
    JSONArray a = new JSONArray();
    for (int i = 0; i < list.size(); i++) a.put(list.get(i));
    return a;
  }
}
