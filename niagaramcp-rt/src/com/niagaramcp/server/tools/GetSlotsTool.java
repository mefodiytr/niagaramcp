/*
 * Copyright 2026 niagaramcp contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.niagaramcp.server.tools;

import javax.baja.naming.BOrd;
import javax.baja.sys.BComplex;
import javax.baja.sys.BObject;
import javax.baja.sys.BFacets;
import javax.baja.sys.Property;
import com.niagaramcp.json.JSONArray;
import com.niagaramcp.json.JSONObject;

/** List all slots of a component with their types and current values. */
public final class GetSlotsTool implements Tool {

  @Override public String name()        { return "getSlots"; }
  @Override public String getCategory() { return "walkthrough-read"; }
  @Override public String description() {
    return "List all properties (slots) of a component by ord. Returns each slot's name, " +
           "type, current value (toString), and selected facets (units, precision).";
  }
  @Override public String schemaJson() {
    return "{\"type\":\"object\",\"properties\":{" +
           "\"ord\":{\"type\":\"string\"}}," +
           "\"required\":[\"ord\"]}";
  }

  @Override
  public String call(JSONObject args) throws Exception {
    String ordStr = args.optString("ord", "");
    if (ordStr == null || ordStr.length() == 0) {
      throw new IllegalArgumentException("Параметр 'ord' обязателен");
    }
    BObject obj = BOrd.make(ordStr).get();
    if (!(obj instanceof BComplex)) {
      throw new IllegalArgumentException("Ord не указывает на BComplex: " + ordStr);
    }
    BComplex c = (BComplex) obj;

    JSONArray slots = new JSONArray();
    Property[] props = c.getPropertiesArray();
    for (int i = 0; i < props.length; i++) {
      Property p = props[i];
      JSONObject s = new JSONObject();
      s.put("name", p.getName());
      try { s.put("type", c.get(p).getType().toString()); } catch (Exception e) {}
      try { s.put("value", String.valueOf(c.get(p))); } catch (Exception e) {}
      try {
        BFacets f = c.getSlotFacets(p);
        if (f != null) {
          JSONObject fJson = new JSONObject();
          String units = f.gets("units", "");
          if (units != null && units.length() > 0) fJson.put("units", units);
          String precision = f.gets("precision", "");
          if (precision != null && precision.length() > 0) fJson.put("precision", precision);
          if (fJson.length() > 0) s.put("facets", fJson);
        }
      } catch (Exception ignored) {}
      slots.put(s);
    }
    JSONObject out = new JSONObject();
    out.put("ord", ordStr);
    out.put("slotCount", slots.length());
    out.put("slots", slots);
    return out.toString();
  }
}
