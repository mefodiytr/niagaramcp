/*
 * Copyright 2026 niagaramcp contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.niagaramcp.server.tools;

import javax.baja.collection.BITable;
import javax.baja.collection.TableCursor;
import javax.baja.naming.BOrd;
import javax.baja.sys.BComponent;
import javax.baja.sys.BObject;
import com.niagaramcp.json.JSONArray;
import com.niagaramcp.json.JSONObject;

/** Find all components of a given Niagara type via BQL. */
public final class FindComponentsByTypeTool implements Tool {

  private static final int DEFAULT_LIMIT = 100;
  private static final int MAX_LIMIT = 1000;

  @Override public String name()        { return "findComponentsByType"; }
  @Override public String getCategory() { return "walkthrough-read"; }
  @Override public String description() {
    return "Find all components matching a Niagara type. typeName accepts either short " +
           "(e.g. 'BNumericPoint') or qualified (e.g. 'control:NumericPoint') form.";
  }
  @Override public String schemaJson() {
    return "{\"type\":\"object\",\"properties\":{" +
           "\"typeName\":{\"type\":\"string\"}," +
           "\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":1000}}," +
           "\"required\":[\"typeName\"]}";
  }

  @Override
  @SuppressWarnings("unchecked")
  public String call(JSONObject args) throws Exception {
    String typeName = args.optString("typeName", "");
    if (typeName == null || typeName.length() == 0) {
      throw new IllegalArgumentException("Параметр 'typeName' обязателен");
    }
    int limit = DEFAULT_LIMIT;
    if (args.has("limit") && !args.isNull("limit")) {
      limit = args.getInt("limit");
      if (limit < 1) limit = 1;
      if (limit > MAX_LIMIT) limit = MAX_LIMIT;
    }

    String typeSpec = canonicalize(typeName);
    String bql = "station:|bql:select * from " + typeSpec;
    BObject obj = BOrd.make(bql).get();
    if (!(obj instanceof BITable)) {
      throw new IllegalArgumentException("BQL did not return a table for typeName=" + typeName);
    }
    BITable<BObject> table = (BITable<BObject>) obj;
    TableCursor<?> cursor = table.cursor();

    JSONArray items = new JSONArray();
    int rows = 0;
    boolean truncated = false;
    while (cursor.next()) {
      if (rows >= limit) { truncated = true; break; }
      Object cell = cursor.get();
      JSONObject item = new JSONObject();
      if (cell instanceof BComponent) {
        BComponent c = (BComponent) cell;
        item.put("name", safe(c.getName()));
        try { item.put("ord", c.getSlotPathOrd().toString()); } catch (Exception e) {}
        try { item.put("displayName", safe(c.getDisplayName(null))); } catch (Exception e) {}
        try { item.put("type", c.getType().toString()); } catch (Exception e) {}
      } else {
        item.put("toString", String.valueOf(cell));
      }
      items.put(item);
      rows++;
    }
    JSONObject out = new JSONObject();
    out.put("typeName", typeSpec);
    out.put("count", rows);
    out.put("truncated", truncated);
    out.put("items", items);
    return out.toString();
  }

  /** Map "BNumericPoint" -> "control:NumericPoint" (best-effort); pass through otherwise. */
  private static String canonicalize(String t) {
    if (t.indexOf(':') > 0) return t;          // already qualified
    if (t.startsWith("B")) {
      String bare = t.substring(1);
      // Common module prefixes — extend as needed; default to 'control:'.
      // Most user-typed names refer to control points.
      return "control:" + bare;
    }
    return t;
  }

  private static String safe(String s) { return s == null ? "" : s; }
}
