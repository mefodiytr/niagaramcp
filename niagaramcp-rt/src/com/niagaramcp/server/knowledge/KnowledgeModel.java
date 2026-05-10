/*
 * Copyright 2026 niagaramcp contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.niagaramcp.server.knowledge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Root in-memory model of the knowledge file. Serialised to YAML/JSON
 * by {@link KnowledgeStore}. See {@code docs/concepts/02-format.md}.
 */
public final class KnowledgeModel {

  /** Bumped on schema-breaking change; current = 1. */
  public static final int CURRENT_SCHEMA_VERSION = 1;

  public int schemaVersion = CURRENT_SCHEMA_VERSION;
  public Map<String,Object> station = new LinkedHashMap<String,Object>();

  public final List<Space>           spaces           = new ArrayList<Space>();
  public final List<EquipmentType>   equipmentTypes   = new ArrayList<EquipmentType>();
  public final List<Equipment>       equipment        = new ArrayList<Equipment>();
  public final List<StandalonePoint> standalonePoints = new ArrayList<StandalonePoint>();

  // ------------------------------------------------------------------
  // Lookups
  // ------------------------------------------------------------------

  public Space getSpace(String id) {
    if (id == null) return null;
    for (Space s : spaces) if (id.equals(s.id)) return s;
    return null;
  }

  public EquipmentType getEquipmentType(String id) {
    if (id == null) return null;
    for (EquipmentType e : equipmentTypes) if (id.equals(e.id)) return e;
    return null;
  }

  public Equipment getEquipment(String id) {
    if (id == null) return null;
    for (Equipment e : equipment) if (id.equals(e.id)) return e;
    return null;
  }

  public StandalonePoint getStandalonePoint(String id) {
    if (id == null) return null;
    for (StandalonePoint p : standalonePoints) if (id.equals(p.id)) return p;
    return null;
  }

  // ------------------------------------------------------------------
  // (De)serialise to generic tree
  // ------------------------------------------------------------------

  @SuppressWarnings("unchecked")
  public static KnowledgeModel fromTree(Object root) throws KnowledgeException {
    KnowledgeModel km = new KnowledgeModel();
    if (root == null) return km;
    if (!(root instanceof Map)) {
      throw new KnowledgeException("knowledge root must be a map; got: "
          + (root == null ? "null" : root.getClass().getSimpleName()));
    }
    Map<String,Object> m = (Map<String,Object>) root;

    Object sv = m.get("schema_version");
    if (sv instanceof Number) km.schemaVersion = ((Number) sv).intValue();
    else if (sv != null) throw new KnowledgeException("schema_version must be an integer");

    if (km.schemaVersion > CURRENT_SCHEMA_VERSION) {
      throw new KnowledgeException("Unsupported schema_version: " + km.schemaVersion
          + " (this build supports up to " + CURRENT_SCHEMA_VERSION + ")");
    }

    Object st = m.get("station");
    if (st instanceof Map) km.station = new LinkedHashMap<String,Object>((Map<String,Object>) st);

    addAll(m.get("spaces"),          km.spaces,           SECTION_SPACE);
    addAll(m.get("equipment_types"), km.equipmentTypes,   SECTION_EQUIPMENT_TYPE);
    addAll(m.get("equipment"),       km.equipment,        SECTION_EQUIPMENT);
    addAll(m.get("points"),          km.standalonePoints, SECTION_POINT);

    return km;
  }

  private static final int SECTION_SPACE          = 1;
  private static final int SECTION_EQUIPMENT_TYPE = 2;
  private static final int SECTION_EQUIPMENT      = 3;
  private static final int SECTION_POINT          = 4;

  @SuppressWarnings("unchecked")
  private static void addAll(Object src, List dst, int kind) throws KnowledgeException {
    if (src == null) return;
    if (!(src instanceof List)) {
      throw new KnowledgeException("section must be a list");
    }
    for (Object item : (List<Object>) src) {
      if (!(item instanceof Map)) continue;
      Map<String,Object> mi = (Map<String,Object>) item;
      switch (kind) {
        case SECTION_SPACE:          dst.add(Space.fromMap(mi));           break;
        case SECTION_EQUIPMENT_TYPE: dst.add(EquipmentType.fromMap(mi));   break;
        case SECTION_EQUIPMENT:      dst.add(Equipment.fromMap(mi));       break;
        case SECTION_POINT:          dst.add(StandalonePoint.fromMap(mi)); break;
        default: throw new KnowledgeException("internal: unknown section kind");
      }
    }
  }

  public Map<String,Object> toTree() {
    Map<String,Object> m = new LinkedHashMap<String,Object>();
    m.put("schema_version", Long.valueOf(schemaVersion));
    if (!station.isEmpty()) m.put("station", station);

    m.put("spaces",          asList(spaces));
    m.put("equipment_types", asListType(equipmentTypes));
    m.put("equipment",       asListEquip(equipment));
    m.put("points",          asListPoint(standalonePoints));
    return m;
  }

  private static List<Object> asList(List<Space> in) {
    List<Object> out = new ArrayList<Object>(in.size());
    for (Space s : in) out.add(s.toMap());
    return out;
  }
  private static List<Object> asListType(List<EquipmentType> in) {
    List<Object> out = new ArrayList<Object>(in.size());
    for (EquipmentType s : in) out.add(s.toMap());
    return out;
  }
  private static List<Object> asListEquip(List<Equipment> in) {
    List<Object> out = new ArrayList<Object>(in.size());
    for (Equipment s : in) out.add(s.toMap());
    return out;
  }
  private static List<Object> asListPoint(List<StandalonePoint> in) {
    List<Object> out = new ArrayList<Object>(in.size());
    for (StandalonePoint s : in) out.add(s.toMap());
    return out;
  }

  // ------------------------------------------------------------------
  // Validation
  // ------------------------------------------------------------------

  /** @return list of human-readable warnings; empty list = clean. */
  public List<String> validate() {
    List<String> warnings = new ArrayList<String>();

    // Unique ids per section
    checkUnique(idsOfSpaces(),         "spaces",          warnings);
    checkUnique(idsOfTypes(),          "equipment_types", warnings);
    checkUnique(idsOfEquipment(),      "equipment",       warnings);
    checkUnique(idsOfStandalone(),     "points",          warnings);

    // References
    for (Equipment e : equipment) {
      if (e.type != null && getEquipmentType(e.type) == null) {
        warnings.add("equipment[" + e.id + "].type references unknown equipment_type: " + e.type);
      }
      if (e.space != null && getSpace(e.space) == null) {
        warnings.add("equipment[" + e.id + "].space references unknown space: " + e.space);
      }
      if (e.ord == null || e.ord.isEmpty()) {
        warnings.add("equipment[" + e.id + "].ord is required");
      }
    }
    for (Space s : spaces) {
      if (s.parent != null && getSpace(s.parent) == null) {
        warnings.add("space[" + s.id + "].parent references unknown space: " + s.parent);
      }
    }
    // Cycles
    for (Space s : spaces) checkSpaceCycle(s, warnings);
    for (EquipmentType t : equipmentTypes) checkTypeCycle(t, warnings);

    return warnings;
  }

  private List<String> idsOfSpaces()      { List<String> r = new ArrayList<String>(); for (Space s : spaces) r.add(s.id); return r; }
  private List<String> idsOfTypes()       { List<String> r = new ArrayList<String>(); for (EquipmentType s : equipmentTypes) r.add(s.id); return r; }
  private List<String> idsOfEquipment()   { List<String> r = new ArrayList<String>(); for (Equipment s : equipment) r.add(s.id); return r; }
  private List<String> idsOfStandalone()  { List<String> r = new ArrayList<String>(); for (StandalonePoint s : standalonePoints) r.add(s.id); return r; }

  private static void checkUnique(List<String> ids, String section, List<String> warnings) {
    Map<String,Integer> seen = new LinkedHashMap<String,Integer>();
    for (String id : ids) {
      if (id == null) { warnings.add(section + "[?] has null id"); continue; }
      Integer n = seen.get(id);
      seen.put(id, n == null ? 1 : n + 1);
    }
    for (Map.Entry<String,Integer> e : seen.entrySet()) {
      if (e.getValue().intValue() > 1) {
        warnings.add(section + " has " + e.getValue() + " entries with id=" + e.getKey());
      }
    }
  }

  private void checkSpaceCycle(Space start, List<String> warnings) {
    String cur = start.parent;
    int hops = 0;
    while (cur != null) {
      if (cur.equals(start.id)) { warnings.add("space[" + start.id + "] has parent cycle"); return; }
      Space next = getSpace(cur);
      if (next == null) return;
      cur = next.parent;
      if (++hops > 50) { warnings.add("space[" + start.id + "] parent chain > 50; possible cycle"); return; }
    }
  }

  private void checkTypeCycle(EquipmentType start, List<String> warnings) {
    String cur = start.extendsId;
    int hops = 0;
    while (cur != null) {
      if (cur.equals(start.id)) { warnings.add("equipment_type[" + start.id + "] has extends cycle"); return; }
      EquipmentType next = getEquipmentType(cur);
      if (next == null) return;
      cur = next.extendsId;
      if (++hops > 50) { warnings.add("equipment_type[" + start.id + "] extends chain > 50"); return; }
    }
  }
}
