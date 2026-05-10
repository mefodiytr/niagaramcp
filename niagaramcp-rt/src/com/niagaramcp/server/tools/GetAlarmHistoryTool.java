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
import javax.baja.sys.BAbsTime;
import javax.baja.sys.Sys;
import com.niagaramcp.json.JSONArray;
import com.niagaramcp.json.JSONObject;

import java.io.IOException;

/** Historical alarms over a time range; optional source filter via prefix or knowledge equipmentId. */
public final class GetAlarmHistoryTool implements Tool {

  private static final int DEFAULT_LIMIT = 200;
  private static final int MAX_LIMIT = 2000;
  private static final long ITERATION_TIMEOUT_MS = 10_000L;

  @Override public String name()        { return "getAlarmHistory"; }
  @Override public String description() {
    return "Historical alarm records via AlarmDbConnection.timeQuery(from, to). " +
           "Optional sourceOrdPrefix filters to a slot subtree (e.g. equipment ord). " +
           "Capped at 2000 rows / 10s iteration; truncation reported in footer.";
  }
  @Override public String schemaJson() {
    return "{\"type\":\"object\",\"properties\":{" +
           "\"from\":{\"type\":\"string\"}," +
           "\"to\":{\"type\":\"string\"}," +
           "\"sourceOrdPrefix\":{\"type\":\"string\"}," +
           "\"limit\":{\"type\":\"integer\"}}," +
           "\"required\":[\"from\"]}";
  }

  @Override
  @SuppressWarnings("unchecked")
  public String call(JSONObject args) throws Exception {
    BAbsTime from = parseTime(args.getString("from"));
    BAbsTime to   = args.has("to") ? parseTime(args.getString("to")) : BAbsTime.now();
    String prefix = args.optString("sourceOrdPrefix", null);
    int limit = args.optInt("limit", DEFAULT_LIMIT);
    if (limit < 1) limit = 1;
    if (limit > MAX_LIMIT) limit = MAX_LIMIT;

    BAlarmService svc = (BAlarmService) Sys.getService(BAlarmService.TYPE);
    if (svc == null) throw new IllegalStateException("BAlarmService not available");
    AlarmDbConnection conn = (AlarmDbConnection) svc.getAlarmDb().getConnection(null);

    JSONArray alarms = new JSONArray();
    int rows = 0;
    boolean truncatedByLimit = false, truncatedByTimeout = false;
    long startMs = System.currentTimeMillis();
    try {
      BITable<BAlarmRecord> tbl = (BITable<BAlarmRecord>) conn.timeQuery(from, to);
      TableCursor<BAlarmRecord> cur = tbl.cursor();
      while (cur.next()) {
        if (System.currentTimeMillis() - startMs >= ITERATION_TIMEOUT_MS) {
          truncatedByTimeout = true; break;
        }
        BAlarmRecord rec = cur.get();
        if (prefix != null && !prefix.isEmpty()) {
          String ord = GetActiveAlarmsTool.sourceOrdString(rec);
          if (ord == null || !ord.startsWith(prefix)) continue;
        }
        if (rows >= limit) { truncatedByLimit = true; break; }
        alarms.put(GetActiveAlarmsTool.toJson(rec));
        rows++;
      }
    } finally {
      try { conn.close(); } catch (Exception ignored) {}
    }
    JSONObject out = new JSONObject();
    out.put("from", from.toString());
    out.put("to", to.toString());
    out.put("count", rows);
    out.put("truncatedByLimit", truncatedByLimit);
    out.put("truncatedByTimeout", truncatedByTimeout);
    out.put("alarms", alarms);
    return out.toString();
  }

  private static BAbsTime parseTime(String s) throws IOException {
    String t = s.trim();
    if (t.length() > 0 && (t.charAt(0) == '-' || Character.isDigit(t.charAt(0)))) {
      try { return BAbsTime.make(Long.parseLong(t)); } catch (NumberFormatException ignored) {}
    }
    return BAbsTime.make(t);
  }
}
