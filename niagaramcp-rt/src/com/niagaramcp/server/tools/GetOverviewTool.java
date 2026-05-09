/*
 * Copyright 2026 niagaramcp contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.niagaramcp.server.tools;

import javax.baja.sys.BComponent;
import javax.baja.sys.Sys;
import com.niagaramcp.json.JSONArray;
import com.niagaramcp.json.JSONObject;

/** Top-level station overview: name, top folders, type counts (children-only by default). */
public final class GetOverviewTool implements Tool {

  @Override public String name()        { return "getOverview"; }
  @Override public String description() {
    return "Top-level station structure summary: station name, immediate top-level slots " +
           "(Drivers, Logic, Services, etc.), and counts of children with Niagara types.";
  }
  @Override public String schemaJson() {
    return "{\"type\":\"object\",\"properties\":{}}";
  }

  @Override
  public String call(JSONObject args) throws Exception {
    BComponent station = Sys.getStation();
    if (station == null) {
      JSONObject err = new JSONObject();
      err.put("error", "no station running");
      return err.toString();
    }
    JSONObject out = new JSONObject();
    out.put("stationName", safe(station.getName()));

    JSONArray topSlots = new JSONArray();
    BComponent[] kids = station.getChildComponents();
    if (kids != null) {
      for (int i = 0; i < kids.length; i++) {
        BComponent c = kids[i];
        JSONObject slot = new JSONObject();
        slot.put("name", safe(c.getName()));
        try { slot.put("type", c.getType().toString()); } catch (Exception e) { slot.put("type", ""); }
        try { slot.put("displayName", safe(c.getDisplayName(null))); } catch (Exception e) {}
        topSlots.put(slot);
      }
    }
    out.put("topSlots", topSlots);
    out.put("topSlotCount", topSlots.length());
    return out.toString();
  }

  private static String safe(String s) { return s == null ? "" : s; }
}
