/*
 * Copyright 2026 niagaramcp contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.niagaramcp.server.tools;

import javax.baja.naming.BOrd;
import javax.baja.sys.BComponent;
import javax.baja.sys.BObject;
import com.niagaramcp.json.JSONObject;

/** Detailed inspection of a single component: type, displayName, child count, parent ord. */
public final class InspectComponentTool implements Tool {

  @Override public String name()        { return "inspectComponent"; }
  @Override public String getCategory() { return "walkthrough-read"; }
  @Override public String description() {
    return "Inspect a single component by ord. Returns JSON with: ord, name, displayName, " +
           "type, parentOrd, childCount.";
  }
  @Override public String schemaJson() {
    return "{\"type\":\"object\",\"properties\":{" +
           "\"ord\":{\"type\":\"string\",\"description\":\"Niagara ord of the component\"}}," +
           "\"required\":[\"ord\"]}";
  }

  @Override
  public String call(JSONObject args) throws Exception {
    String ordStr = args.optString("ord", "");
    if (ordStr == null || ordStr.length() == 0) {
      throw new IllegalArgumentException("Параметр 'ord' обязателен");
    }
    BObject obj = BOrd.make(ordStr).get();
    if (!(obj instanceof BComponent)) {
      throw new IllegalArgumentException("Ord не указывает на BComponent: " + ordStr);
    }
    BComponent c = (BComponent) obj;

    JSONObject r = new JSONObject();
    r.put("ord", ordStr);
    r.put("name", safe(c.getName()));
    try { r.put("displayName", safe(c.getDisplayName(null))); } catch (Exception e) { r.put("displayName", ""); }
    try { r.put("type", c.getType().toString()); } catch (Exception e) { r.put("type", ""); }
    BComponent[] kids = c.getChildComponents();
    r.put("childCount", kids == null ? 0 : kids.length);
    try {
      BComponent parent = (BComponent) c.getParent();
      if (parent != null) r.put("parentOrd", parent.getSlotPathOrd().toString());
    } catch (Exception ignored) {}
    return r.toString();
  }

  private static String safe(String s) { return s == null ? "" : s; }
}
