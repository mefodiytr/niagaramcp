/*
 * Copyright 2026 niagaramcp contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.niagaramcp.server.tools;

import javax.baja.alarm.BAlarmService;
import javax.baja.history.BHistoryService;
import javax.baja.sys.Sys;
import com.niagaramcp.json.JSONObject;
import com.niagaramcp.server.BMcpPlatformService;
import com.niagaramcp.server.NiagaraFileUtil;
import com.niagaramcp.server.knowledge.KnowledgeStore;

import java.io.File;

/** Health probe of underlying Niagara services + knowledge file/audit log. */
public final class GetServiceHealthTool implements Tool {

  @Override public String name()        { return "getServiceHealth"; }
  @Override public String getCategory() { return "diagnostic"; }
  @Override public String description() {
    return "Health snapshot. Returns {alarmService, historyService, knowledgeFile" +
           "{readable,writable,exists}, knowledgeAuditLog{writable}, sampleResource}.";
  }
  @Override public String schemaJson() { return ToolSchemaHelpers.emptySchema(); }

  @Override
  public String call(JSONObject args) throws Exception {
    JSONObject out = new JSONObject();
    out.put("alarmService",   svcStatus(BAlarmService.TYPE));
    out.put("historyService", svcStatus(BHistoryService.TYPE));

    KnowledgeStore ks = BMcpPlatformService.getKnowledgeStore();
    JSONObject kfJson = new JSONObject();
    if (ks != null) {
      // Persisted as a station-config property (see KnowledgeStore class
      // javadoc / issue #1) — always readable/writable through this API,
      // unlike an OS file which can genuinely be denied.
      kfJson.put("location", ks.describeLocation());
      kfJson.put("exists", ks.exists());
      kfJson.put("readable", true);
      kfJson.put("writable", true);
    } else {
      kfJson.put("error", "knowledge store not initialised");
    }
    out.put("knowledgeFile", kfJson);

    // Best-effort secondary audit trail — a plain OS file, not guaranteed
    // to be writable on every Niagara distribution.
    JSONObject auditJson = new JSONObject();
    try {
      File dir = new File(Sys.getNiagaraUserHome(), "niagaramcp");
      File audit = new File(dir, "knowledge.audit.log");
      auditJson.put("path", audit.getAbsolutePath());
      auditJson.put("writable", NiagaraFileUtil.exists(audit) ? NiagaraFileUtil.canWrite(audit)
                                                               : NiagaraFileUtil.canWrite(dir));
    } catch (Throwable t) {
      auditJson.put("writable", false);
      auditJson.put("note", "best-effort secondary log; unavailable on this platform");
    }
    out.put("knowledgeAuditLog", auditJson);

    String sample;
    try {
      String s = KnowledgeStore.readSampleResource();
      sample = (s != null && s.length() > 0) ? "ok" : "empty";
    } catch (Exception e) {
      sample = "missing: " + e.getMessage();
    }
    out.put("sampleResource", sample);

    return out.toString();
  }

  private static String svcStatus(javax.baja.sys.Type type) {
    try {
      Object svc = Sys.getService(type);
      return svc == null ? "unavailable" : "ok";
    } catch (Exception e) {
      return "error: " + e.getMessage();
    }
  }
}
