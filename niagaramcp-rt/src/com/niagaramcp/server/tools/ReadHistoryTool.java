/*
 * Copyright 2026 niagaramcp contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.niagaramcp.server.tools;

import javax.baja.collection.BITable;
import javax.baja.collection.TableCursor;
import javax.baja.control.BControlPoint;
import javax.baja.history.BHistoryRecord;
import javax.baja.history.BHistoryService;
import javax.baja.history.BHistorySpace;
import javax.baja.history.BIHistory;
import javax.baja.history.BNumericTrendRecord;
import javax.baja.history.HistorySpaceConnection;
import javax.baja.history.ext.BHistoryExt;
import javax.baja.naming.BOrd;
import javax.baja.sys.BAbsTime;
import javax.baja.sys.BComponent;
import javax.baja.sys.BObject;
import javax.baja.sys.Sys;
import com.niagaramcp.json.JSONArray;
import com.niagaramcp.json.JSONObject;

/**
 * Read history records from a BControlPoint's BHistoryExt or directly from
 * a BHistoryExt component, between {@code from} and {@code to}.
 *
 * <p>Aggregation (avg/min/max/count) is computed client-side; Niagara
 * exposes no server-side aggregation per recon §1.5.
 */
public final class ReadHistoryTool implements Tool {

  private static final int  DEFAULT_LIMIT = 1000;
  private static final int  MAX_LIMIT = 10_000;
  private static final long ITERATION_TIMEOUT_MS = 10_000L;

  @Override public String name()        { return "readHistory"; }
  @Override public String description() {
    return "Read history records for a control point or BHistoryExt between from/to (ISO " +
           "datetime or epoch ms). Optional aggregation: none|avg|min|max|count " +
           "(client-side; Niagara has no server-side aggregation). limit caps row count " +
           "(default 1000, max 10000); a 10s iteration timeout also applies.";
  }
  @Override public String schemaJson() {
    return "{\"type\":\"object\",\"properties\":{" +
           "\"ord\":{\"type\":\"string\"}," +
           "\"from\":{\"type\":\"string\",\"description\":\"ISO datetime or epoch ms\"}," +
           "\"to\":{\"type\":\"string\",\"description\":\"ISO datetime or epoch ms; default now\"}," +
           "\"limit\":{\"type\":\"integer\"}," +
           "\"aggregation\":{\"type\":\"string\",\"enum\":[\"none\",\"avg\",\"min\",\"max\",\"count\"]}}," +
           "\"required\":[\"ord\",\"from\"]}";
  }

  @Override
  public String call(JSONObject args) throws Exception {
    String ordStr = args.getString("ord");
    String fromS  = args.getString("from");
    String toS    = args.optString("to", null);
    int limit     = args.optInt("limit", DEFAULT_LIMIT);
    if (limit < 1) limit = 1;
    if (limit > MAX_LIMIT) limit = MAX_LIMIT;
    String agg    = args.optString("aggregation", "none");

    BAbsTime from = parseTime(fromS);
    BAbsTime to   = (toS == null || toS.isEmpty()) ? BAbsTime.now() : parseTime(toS);

    BObject obj = BOrd.make(ordStr).get();
    BHistoryExt ext;
    if (obj instanceof BHistoryExt) {
      ext = (BHistoryExt) obj;
    } else if (obj instanceof BControlPoint) {
      ext = findHistoryExt((BControlPoint) obj);
      if (ext == null) {
        throw new IllegalArgumentException(
            "BControlPoint has no BHistoryExt child: " + ordStr);
      }
    } else {
      throw new IllegalArgumentException(
          "Ord does not resolve to BControlPoint or BHistoryExt: " + ordStr);
    }

    BIHistory history = ext.getHistory();
    BHistoryService svc = (BHistoryService) Sys.getService(BHistoryService.TYPE);
    if (svc == null) throw new IllegalStateException("BHistoryService not available");
    BHistorySpace space = (BHistorySpace) svc.getDatabase();
    HistorySpaceConnection conn = space.getConnection(null);
    JSONArray records = new JSONArray();
    int rows = 0;
    boolean truncatedByLimit = false, truncatedByTimeout = false;
    long startMs = System.currentTimeMillis();
    // Aggregation accumulators
    double aggMin = Double.POSITIVE_INFINITY, aggMax = Double.NEGATIVE_INFINITY, aggSum = 0.0;
    int aggCount = 0;
    boolean aggregating = !"none".equals(agg);

    try {
      BITable<BHistoryRecord> tbl = conn.timeQuery(history, from, to);
      TableCursor<BHistoryRecord> cursor = tbl.cursor();
      while (cursor.next()) {
        if (rows >= limit) { truncatedByLimit = true; break; }
        if (System.currentTimeMillis() - startMs >= ITERATION_TIMEOUT_MS) {
          truncatedByTimeout = true; break;
        }
        BHistoryRecord rec = (BHistoryRecord) cursor.get();
        if (aggregating) {
          if (rec instanceof BNumericTrendRecord) {
            double v = ((BNumericTrendRecord) rec).getValue();
            if (v < aggMin) aggMin = v;
            if (v > aggMax) aggMax = v;
            aggSum += v;
          }
          aggCount++;
        } else {
          JSONObject r = new JSONObject();
          try { r.put("timestamp", rec.getTimestamp().toString()); } catch (Exception e) {}
          if (rec instanceof BNumericTrendRecord) {
            r.put("value", ((BNumericTrendRecord) rec).getValue());
          } else {
            r.put("toString", rec.toString());
          }
          records.put(r);
        }
        rows++;
      }
    } finally {
      try { conn.close(); } catch (Exception ignored) {}
    }

    JSONObject out = new JSONObject();
    out.put("ord", ordStr);
    out.put("from", from.toString());
    out.put("to", to.toString());
    out.put("aggregation", agg);
    out.put("rows", rows);
    out.put("truncatedByLimit", truncatedByLimit);
    out.put("truncatedByTimeout", truncatedByTimeout);
    if (aggregating) {
      out.put("count", aggCount);
      if (aggCount > 0 && aggMin != Double.POSITIVE_INFINITY) {
        out.put("min", aggMin);
        out.put("max", aggMax);
        out.put("avg", aggSum / aggCount);
        out.put("sum", aggSum);
      }
    } else {
      out.put("records", records);
    }
    return out.toString();
  }

  /** Walk children of a control point looking for any BHistoryExt. */
  private static BHistoryExt findHistoryExt(BControlPoint p) {
    BComponent[] kids = p.getChildComponents();
    if (kids == null) return null;
    for (BComponent c : kids) if (c instanceof BHistoryExt) return (BHistoryExt) c;
    return null;
  }

  /** Parse either an ISO datetime string or epoch-ms string. */
  private static BAbsTime parseTime(String s) throws java.io.IOException {
    String t = s.trim();
    if (t.length() > 0 && (t.charAt(0) == '-' || Character.isDigit(t.charAt(0)))) {
      // try long
      try { return BAbsTime.make(Long.parseLong(t)); } catch (NumberFormatException ignored) {}
    }
    return BAbsTime.make(t);
  }
}
