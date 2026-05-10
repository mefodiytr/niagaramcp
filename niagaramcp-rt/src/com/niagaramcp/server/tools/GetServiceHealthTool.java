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
import com.niagaramcp.server.knowledge.KnowledgeStore;

import java.io.File;

/** Health probe of underlying Niagara services + knowledge file/audit log. */
public final class GetServiceHealthTool implements Tool {

  @Override public String name()        { return "getServiceHealth"; }
  @Override public String description() {
    return "Health snapshot. Returns {alarmService, historyService, knowledgeFile" +
           "{readable,writable,exists}, knowledgeAuditLog{writable}, sampleResource}.";
  }
  @Override public String schemaJson() { return "{\"type\":\"object\",\"properties\":{}}"; }

  @Override
  public String call(JSONObject args) throws Exception {
    JSONObject out = new JSONObject();
    out.put("alarmService",   svcStatus(BAlarmService.TYPE));
    out.put("historyService", svcStatus(BHistoryService.TYPE));

    KnowledgeStore ks = BMcpPlatformService.getKnowledgeStore();
    JSONObject kfJson = new JSONObject();
    if (ks != null && ks.getFile() != null) {
      File f = ks.getFile();
      kfJson.put("path", f.getAbsolutePath());
      kfJson.put("exists", f.exists());
      kfJson.put("readable", f.exists() && f.canRead());
      // writable applies to either the file itself OR the parent dir for create
      File writeProbe = f.exists() ? f : f.getParentFile();
      kfJson.put("writable", writeProbe != null && writeProbe.canWrite());
    } else {
      kfJson.put("error", "knowledge store not initialised");
    }
    out.put("knowledgeFile", kfJson);

    JSONObject auditJson = new JSONObject();
    if (ks != null && ks.getFile() != null) {
      File parent = ks.getFile().getParentFile();
      File audit = (parent != null) ? new File(parent, "knowledge.audit.log") : null;
      auditJson.put("path", audit == null ? "" : audit.getAbsolutePath());
      auditJson.put("writable", audit != null && (audit.exists() ? audit.canWrite()
                                                                 : (parent != null && parent.canWrite())));
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
