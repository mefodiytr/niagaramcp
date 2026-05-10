/*
 * Copyright 2026 niagaramcp contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.niagaramcp.server.tools;

import javax.baja.alarm.AlarmDbConnection;
import javax.baja.alarm.BAlarmRecord;
import javax.baja.alarm.BAlarmService;
import javax.baja.collection.BITable;
import javax.baja.collection.TableCursor;
import javax.baja.sys.Sys;
import com.niagaramcp.json.JSONArray;
import com.niagaramcp.json.JSONObject;

/** Currently-open alarms via {@code AlarmDbConnection.getOpenAlarms()}. */
public final class GetActiveAlarmsTool implements Tool {

  private static final int DEFAULT_LIMIT = 100;
  private static final int MAX_LIMIT = 1000;

  @Override public String name()        { return "getActiveAlarms"; }
  @Override public String description() {
    return "List currently-open alarms (off-normal/fault, not yet returned to normal). " +
           "Optional sourceOrdPrefix filters by source ord starting with that string.";
  }
  @Override public String schemaJson() {
    return "{\"type\":\"object\",\"properties\":{" +
           "\"sourceOrdPrefix\":{\"type\":\"string\"}," +
           "\"limit\":{\"type\":\"integer\"}}}";
  }

  @Override
  @SuppressWarnings("unchecked")
  public String call(JSONObject args) throws Exception {
    String prefix = args.optString("sourceOrdPrefix", null);
    int limit = args.optInt("limit", DEFAULT_LIMIT);
    if (limit < 1) limit = 1;
    if (limit > MAX_LIMIT) limit = MAX_LIMIT;

    BAlarmService svc = (BAlarmService) Sys.getService(BAlarmService.TYPE);
    if (svc == null) throw new IllegalStateException("BAlarmService not available");
    AlarmDbConnection conn = (AlarmDbConnection) svc.getAlarmDb().getConnection(null);
    JSONArray alarms = new JSONArray();
    int rows = 0;
    boolean truncated = false;
    try {
      BITable<BAlarmRecord> tbl = (BITable<BAlarmRecord>) conn.getOpenAlarms();
      TableCursor<BAlarmRecord> cur = tbl.cursor();
      while (cur.next()) {
        BAlarmRecord rec = cur.get();
        if (prefix != null && !prefix.isEmpty()) {
          String ord = sourceOrdString(rec);
          if (ord == null || !ord.startsWith(prefix)) continue;
        }
        if (rows >= limit) { truncated = true; break; }
        alarms.put(toJson(rec));
        rows++;
      }
    } finally {
      try { conn.close(); } catch (Exception ignored) {}
    }
    JSONObject out = new JSONObject();
    out.put("count", rows);
    out.put("truncated", truncated);
    out.put("alarms", alarms);
    return out.toString();
  }

  static String sourceOrdString(BAlarmRecord rec) {
    // BAlarmRecord.getSource() returns BOrdList directly (not BAlarmSource — recon §2.5
    // mis-attributed the wrapper). toString = canonical text representation.
    try { return rec.getSource().toString(); } catch (Exception e) { return null; }
  }

  static JSONObject toJson(BAlarmRecord rec) {
    JSONObject j = new JSONObject();
    try { j.put("uuid", rec.getUuid().toString()); } catch (Exception e) {}
    try { j.put("timestamp", rec.getTimestamp().toString()); } catch (Exception e) {}
    try { j.put("sourceOrd", rec.getSource().toString()); } catch (Exception e) {}
    // sourceName via SOURCE_NAME facet
    try {
      Object n = rec.getAlarmFacet(BAlarmRecord.SOURCE_NAME);
      if (n != null) j.put("sourceName", n.toString());
    } catch (Exception e) {}
    try { j.put("priority", rec.getPriority()); } catch (Exception e) {}
    try { j.put("alarmClass", rec.getAlarmClass()); } catch (Exception e) {}
    try { j.put("ackState", String.valueOf(rec.getAckState())); } catch (Exception e) {}
    try { j.put("transition", String.valueOf(rec.getAlarmTransition())); } catch (Exception e) {}
    try { j.put("isOpen", rec.isOpen()); } catch (Exception e) {}
    try { j.put("isAcknowledged", rec.isAcknowledged()); } catch (Exception e) {}
    try { j.put("isNormal", rec.isNormal()); } catch (Exception e) {}
    try {
      Object v = rec.getAlarmValue();
      if (v != null) j.put("alarmValue", v.toString());
    } catch (Exception e) {}
    try {
      Object t = rec.getAckTime();
      if (t != null) j.put("ackTime", t.toString());
    } catch (Exception e) {}
    try {
      Object t = rec.getNormalTime();
      if (t != null) j.put("normalTime", t.toString());
    } catch (Exception e) {}
    return j;
  }
}
