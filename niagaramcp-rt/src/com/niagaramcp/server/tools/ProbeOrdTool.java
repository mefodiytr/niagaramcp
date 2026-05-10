/*
 * Copyright 2026 niagaramcp contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.niagaramcp.server.tools;

import javax.baja.alarm.BAlarmSource;
import javax.baja.control.BControlPoint;
import javax.baja.control.BIWritablePoint;
import javax.baja.history.ext.BHistoryExt;
import javax.baja.naming.BOrd;
import javax.baja.sys.BComplex;
import javax.baja.sys.BComponent;
import javax.baja.sys.BObject;
import com.niagaramcp.json.JSONArray;
import com.niagaramcp.json.JSONObject;

/** Diagnostic probe for an ord — exists, type, slot count, point/writable/history/alarm flags. */
public final class ProbeOrdTool implements Tool {

  @Override public String name()        { return "probeOrd"; }
  @Override public String getCategory() { return "diagnostic"; }
  @Override public String description() {
    return "Probe a Niagara ord for existence and key attributes. Returns " +
           "{exists, type, displayName, parentOrd, slotCount, isControlPoint, " +
           "isWritable, hasHistoryExt, historyExtCount, historyExtIds, " +
           "isAlarmSource}. Unresolvable ords yield {exists:false} (no error).";
  }
  @Override public String schemaJson() {
    return "{\"type\":\"object\",\"properties\":{" +
           "\"ord\":{\"type\":\"string\"}}," +
           "\"required\":[\"ord\"]}";
  }

  @Override
  public String call(JSONObject args) throws Exception {
    String ordStr = args.getString("ord");
    JSONObject out = new JSONObject();

    BObject obj;
    try {
      obj = BOrd.make(ordStr).get();
    } catch (Exception e) {
      out.put("exists", false);
      out.put("error", "ord not resolvable: " + e.getMessage());
      return out.toString();
    }
    if (obj == null) {
      out.put("exists", false);
      out.put("error", "ord resolved to null");
      return out.toString();
    }

    out.put("exists", true);
    try { out.put("type", obj.getType().toString()); } catch (Exception e) {}

    if (obj instanceof BComponent) {
      BComponent c = (BComponent) obj;
      try { out.put("displayName", safe(c.getDisplayName(null))); } catch (Exception e) {}
      try {
        BComponent parent = (BComponent) c.getParent();
        if (parent != null) out.put("parentOrd", parent.getSlotPathOrd().toString());
      } catch (Exception e) {}
    }

    if (obj instanceof BComplex) {
      BComplex cx = (BComplex) obj;
      try { out.put("slotCount", cx.getSlotCount()); } catch (Exception e) {}
    }

    out.put("isControlPoint", obj instanceof BControlPoint);
    out.put("isWritable",     obj instanceof BIWritablePoint);
    out.put("isAlarmSource",  obj instanceof BAlarmSource);

    // History extension(s)
    int historyCount = 0;
    JSONArray historyIds = new JSONArray();
    if (obj instanceof BComponent) {
      BComponent[] kids = ((BComponent) obj).getChildComponents();
      if (kids != null) {
        for (int i = 0; i < kids.length; i++) {
          if (kids[i] instanceof BHistoryExt) {
            historyCount++;
            try { historyIds.put(kids[i].getName()); } catch (Exception e) {}
          }
        }
      }
    }
    out.put("hasHistoryExt", historyCount > 0);
    out.put("historyExtCount", historyCount);
    out.put("historyExtIds", historyIds);

    return out.toString();
  }

  private static String safe(String s) { return s == null ? "" : s; }
}
