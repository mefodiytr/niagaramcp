/*
 * Copyright 2026 niagaramcp contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.niagaramcp.server.tools;

import com.niagaramcp.json.JSONObject;
import com.niagaramcp.server.BMcpPlatformService;
import com.niagaramcp.server.knowledge.Equipment;
import com.niagaramcp.server.knowledge.EquipmentType;
import com.niagaramcp.server.knowledge.KnowledgeModel;
import com.niagaramcp.server.knowledge.KnowledgeStore;
import com.niagaramcp.server.knowledge.Space;
import com.niagaramcp.server.knowledge.StandalonePoint;
import com.niagaramcp.server.yaml.YamlReader;

/** Import knowledge from a YAML/JSON string or the bundled sample. mode: merge|replace. */
public final class ImportKnowledgeTool implements Tool {

  @Override public String name()        { return "importKnowledge"; }
  @Override public String getCategory() { return "management"; }
  @Override public String description() {
    return "Import a knowledge document. content is the raw YAML/JSON text (autodetected); " +
           "OR set source='sample' to load the jar-bundled sample-knowledge.yaml. " +
           "mode='merge' adds new entries (skipping id collisions); mode='replace' wipes " +
           "and replaces the entire model.";
  }
  @Override public String schemaJson() {
    return "{\"type\":\"object\",\"properties\":{" +
           "\"content\":{\"type\":\"string\"}," +
           "\"source\":{\"type\":\"string\",\"enum\":[\"sample\"]}," +
           "\"mode\":{\"type\":\"string\",\"enum\":[\"merge\",\"replace\"]}}}";
  }

  @Override
  public String call(JSONObject args) throws Exception {
    KnowledgeStore ks = BMcpPlatformService.getKnowledgeStore();
    if (ks == null) throw new IllegalStateException("Knowledge store not available");
    String mode = args.optString("mode", "merge");
    boolean replace = "replace".equals(mode);

    String content;
    if ("sample".equals(args.optString("source", null))) {
      content = KnowledgeStore.readSampleResource();
    } else {
      content = args.optString("content", null);
      if (content == null || content.length() == 0) {
        throw new IllegalArgumentException("Either 'content' (text) or source='sample' is required");
      }
    }

    Object tree = YamlReader.parse(content);
    KnowledgeModel incoming = KnowledgeModel.fromTree(tree);

    KnowledgeModel km = ks.getModel();
    int added = 0, skipped = 0;
    if (replace) {
      km.spaces.clear();
      km.equipmentTypes.clear();
      km.equipment.clear();
      km.standalonePoints.clear();
      km.station = incoming.station;
      km.schemaVersion = incoming.schemaVersion;
    }
    for (Space s : incoming.spaces) {
      if (km.getSpace(s.id) == null) { km.spaces.add(s); added++; } else skipped++;
    }
    for (EquipmentType e : incoming.equipmentTypes) {
      if (km.getEquipmentType(e.id) == null) { km.equipmentTypes.add(e); added++; } else skipped++;
    }
    for (Equipment e : incoming.equipment) {
      if (km.getEquipment(e.id) == null) { km.equipment.add(e); added++; } else skipped++;
    }
    for (StandalonePoint p : incoming.standalonePoints) {
      if (km.getStandalonePoint(p.id) == null) { km.standalonePoints.add(p); added++; } else skipped++;
    }
    ks.save("importKnowledge", mode + "+" + added, null);

    JSONObject r = new JSONObject();
    r.put("ok", true);
    r.put("mode", mode);
    r.put("added", added);
    r.put("skipped", skipped);
    return r.toString();
  }
}
