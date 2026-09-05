/*
 * Copyright 2026 niagaramcp contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.niagaramcp.server.tools;

import javax.baja.alarm.BAlarmService;
import javax.baja.history.BHistoryService;
import javax.baja.sys.Sys;
import com.niagaramcp.json.JSONArray;
import com.niagaramcp.json.JSONObject;
import com.niagaramcp.server.BMcpPlatformService;
import com.niagaramcp.server.McpSessions;
import com.niagaramcp.server.NiagaraFileUtil;
import com.niagaramcp.server.knowledge.KnowledgeStore;

import java.io.File;
import java.nio.charset.Charset;
import java.util.List;

/**
 * Combined diagnostic snapshot — server + sessions + knowledge + health +
 * tail of audit log. Reuses logic of the four single-purpose v0.3.1
 * diagnostic tools but in one round-trip for ops dashboards.
 */
public final class GetDiagnosticDumpTool implements Tool {

  private static final int AUDIT_TAIL_MAX_LINES = 20;
  private static final Charset UTF8 = Charset.forName("UTF-8");

  @Override public String name()        { return "getDiagnosticDump"; }
  @Override public String getCategory() { return "diagnostic"; }
  @Override public String description() {
    return "One-shot diagnostic snapshot combining server identity, active " +
           "sessions, knowledge store stats, service health, and the last " +
           "20 lines of the knowledge audit log.";
  }
  @Override public String schemaJson() { return ToolSchemaHelpers.emptySchema(); }

  @Override
  public String call(JSONObject args) throws Exception {
    JSONObject root = new JSONObject();

    // server
    JSONObject server = new JSONObject();
    server.put("version", GetServerInfoTool.NIAGARAMCP_VERSION);
    long startMs = BMcpPlatformService.getServiceStartTimeMs();
    server.put("uptimeSeconds", startMs == 0 ? 0 : (System.currentTimeMillis() - startMs) / 1000L);
    JSONArray transports = new JSONArray();
    if (BMcpPlatformService.sseEnabled())        transports.put("sse");
    if (BMcpPlatformService.streamableEnabled()) transports.put("streamable-http");
    server.put("transports", transports);
    root.put("server", server);

    // sessions — McpSessions tracks both SSE and Streamable; expose count + ids
    JSONObject sessions = new JSONObject();
    sessions.put("totalActiveCount", McpSessions.activeCount());
    // Per-transport split would require McpSessions to expose typed iteration;
    // not exposed in v0.3.1 so we report the aggregate. Future enhancement.
    root.put("sessions", sessions);

    // knowledge
    JSONObject knowledge = new JSONObject();
    KnowledgeStore ks = BMcpPlatformService.getKnowledgeStore();
    if (ks != null) {
      knowledge.put("location", ks.describeLocation());
      knowledge.put("sizeBytes", ks.sizeBytes());
      knowledge.put("exists",   ks.exists());
      knowledge.put("equipmentCount",     ks.getModel().equipment.size());
      knowledge.put("spaceCount",         ks.getModel().spaces.size());
      knowledge.put("equipmentTypeCount", ks.getModel().equipmentTypes.size());
      knowledge.put("standalonePointCount", ks.getModel().standalonePoints.size());
    }
    root.put("knowledge", knowledge);

    // health
    JSONObject health = new JSONObject();
    health.put("alarmService",   svcStatus(BAlarmService.TYPE));
    health.put("historyService", svcStatus(BHistoryService.TYPE));
    if (ks != null) {
      health.put("knowledgeFile", ks.exists() ? "ok" : "empty");
    } else {
      health.put("knowledgeFile", "no-store");
    }
    root.put("health", health);

    // audit log tail
    root.put("auditLogTail", readAuditTail(ks));

    return root.toString();
  }

  /**
   * @return last {@link #AUDIT_TAIL_MAX_LINES} lines of the best-effort
   * secondary knowledge audit log, or empty array — this is a plain OS
   * file and isn't guaranteed to be readable on every Niagara distribution.
   */
  private static JSONArray readAuditTail(KnowledgeStore ks) {
    JSONArray arr = new JSONArray();
    if (ks == null) return arr;
    try {
      File audit = new File(new File(Sys.getNiagaraUserHome(), "niagaramcp"), "knowledge.audit.log");
      if (!NiagaraFileUtil.exists(audit)) return arr;
      List<String> lines = NiagaraFileUtil.readLines(audit);
      int from = Math.max(0, lines.size() - AUDIT_TAIL_MAX_LINES);
      for (int i = from; i < lines.size(); i++) arr.put(lines.get(i));
    } catch (Throwable ignored) {
      // best-effort only; report empty tail rather than failing whole dump
    }
    return arr;
  }

  private static String svcStatus(javax.baja.sys.Type type) {
    try {
      Object svc = Sys.getService(type);
      return svc == null ? "unavailable" : "ok";
    } catch (Exception e) {
      return "error";
    }
  }
}
