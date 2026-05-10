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
import com.niagaramcp.server.BMcpPlatformService;
import com.niagaramcp.server.knowledge.Equipment;
import com.niagaramcp.server.knowledge.KnowledgeModel;
import com.niagaramcp.server.knowledge.KnowledgeStore;
import com.niagaramcp.server.knowledge.StandalonePoint;

import java.util.HashSet;
import java.util.Set;

/** Find Niagara components that are not referenced by any knowledge entry. */
public final class FindUnmappedComponentsTool implements Tool {

  private static final int DEFAULT_LIMIT = 100;
  private static final int MAX_LIMIT = 1000;

  @Override public String name()        { return "findUnmappedComponents"; }
  @Override public String getCategory() { return "management"; }
  @Override public String description() {
    return "List Niagara components matching typeName that are NOT referenced by any " +
           "knowledge equipment.ord, equipment.points, or standalonePoint.ord. Useful " +
           "to find points still needing walkthrough coverage.";
  }
  @Override public String schemaJson() {
    return "{\"type\":\"object\",\"properties\":{" +
           "\"typeName\":{\"type\":\"string\",\"description\":\"e.g. BControlPoint\"}," +
           "\"limit\":{\"type\":\"integer\"}}," +
           "\"required\":[\"typeName\"]}";
  }

  @Override
  @SuppressWarnings("unchecked")
  public String call(JSONObject args) throws Exception {
    String typeName = args.getString("typeName");
    int limit = args.optInt("limit", DEFAULT_LIMIT);
    if (limit < 1) limit = 1;
    if (limit > MAX_LIMIT) limit = MAX_LIMIT;

    KnowledgeStore ks = BMcpPlatformService.getKnowledgeStore();
    if (ks == null) throw new IllegalStateException("Knowledge store not available");
    KnowledgeModel km = ks.getModel();

    // Collect all known ords (and their prefixes for equipment-root coverage)
    Set<String> known = new HashSet<String>();
    for (Equipment e : km.equipment) {
      if (e.ord != null) known.add(e.ord);
      for (String p : e.points.values()) if (p != null) known.add(p);
    }
    for (StandalonePoint p : km.standalonePoints) if (p.ord != null) known.add(p.ord);

    String typeSpec = typeName.startsWith("B") && typeName.indexOf(':') < 0
        ? "control:" + typeName.substring(1) : typeName;
    BObject obj = BOrd.make("station:|bql:select * from " + typeSpec).get();
    if (!(obj instanceof BITable)) {
      throw new IllegalArgumentException("BQL did not return a table for type: " + typeName);
    }
    BITable<BObject> table = (BITable<BObject>) obj;
    TableCursor<?> cur = table.cursor();

    JSONArray items = new JSONArray();
    int rows = 0, scanned = 0;
    boolean truncated = false;
    while (cur.next()) {
      scanned++;
      Object cell = cur.get();
      if (!(cell instanceof BComponent)) continue;
      BComponent c = (BComponent) cell;
      String ord;
      try { ord = c.getSlotPathOrd().toString(); } catch (Exception e) { continue; }
      if (known.contains(ord)) continue;
      // Also skip if any known ord is a prefix (component is inside known equipment)
      boolean covered = false;
      for (String k : known) { if (k != null && ord.startsWith(k + "/")) { covered = true; break; } }
      if (covered) continue;
      if (rows >= limit) { truncated = true; break; }
      JSONObject item = new JSONObject();
      item.put("ord", ord);
      try { item.put("name", c.getName()); } catch (Exception e) {}
      items.put(item);
      rows++;
    }

    JSONObject out = new JSONObject();
    out.put("typeName", typeSpec);
    out.put("scanned", scanned);
    out.put("unmappedCount", rows);
    out.put("truncated", truncated);
    out.put("items", items);
    return out.toString();
  }
}
