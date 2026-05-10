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
 * Equipment type / kind template (AHU, Chiller, etc.). See
 * {@code docs/concepts/02-format.md} §Equipment Types.
 */
public final class EquipmentType {

  public static final class TypicalPoint {
    public String role;
    public String kind;
    public List<String> slotPatterns = new ArrayList<String>();
    public boolean required = false;

    @SuppressWarnings("unchecked")
    public static TypicalPoint fromMap(Map<String,Object> m) {
      TypicalPoint t = new TypicalPoint();
      t.role     = (String) m.get("role");
      t.kind     = (String) m.get("kind");
      Object req = m.get("required");
      t.required = (req instanceof Boolean) ? ((Boolean) req).booleanValue() : false;
      Object sp  = m.get("slot_patterns");
      if (sp instanceof List) {
        for (Object o : (List<Object>) sp) t.slotPatterns.add(String.valueOf(o));
      }
      return t;
    }

    public Map<String,Object> toMap() {
      Map<String,Object> m = new LinkedHashMap<String,Object>();
      if (role != null) m.put("role", role);
      if (kind != null) m.put("kind", kind);
      if (!slotPatterns.isEmpty()) m.put("slot_patterns", new ArrayList<Object>(slotPatterns));
      if (required) m.put("required", Boolean.TRUE);
      return m;
    }
  }

  public String id;
  public String name;
  public List<String> aliases = new ArrayList<String>();
  public String description;
  public String extendsId;     // 'extends' is a reserved keyword
  public List<TypicalPoint> typicalPoints = new ArrayList<TypicalPoint>();

  public EquipmentType() {}

  @SuppressWarnings("unchecked")
  public static EquipmentType fromMap(Map<String,Object> m) {
    EquipmentType e = new EquipmentType();
    e.id          = (String) m.get("id");
    e.name        = (String) m.get("name");
    e.description = (String) m.get("description");
    e.extendsId   = (String) m.get("extends");
    Object al = m.get("aliases");
    if (al instanceof List) for (Object o : (List<Object>) al) e.aliases.add(String.valueOf(o));
    Object tp = m.get("typical_points");
    if (tp instanceof List) {
      for (Object o : (List<Object>) tp) {
        if (o instanceof Map) e.typicalPoints.add(TypicalPoint.fromMap((Map<String,Object>) o));
      }
    }
    return e;
  }

  public Map<String,Object> toMap() {
    Map<String,Object> m = new LinkedHashMap<String,Object>();
    if (id          != null) m.put("id",          id);
    if (name        != null) m.put("name",        name);
    if (!aliases.isEmpty())  m.put("aliases",     new ArrayList<Object>(aliases));
    if (description != null) m.put("description", description);
    if (extendsId   != null) m.put("extends",     extendsId);
    if (!typicalPoints.isEmpty()) {
      List<Object> tp = new ArrayList<Object>();
      for (TypicalPoint t : typicalPoints) tp.add(t.toMap());
      m.put("typical_points", tp);
    }
    return m;
  }
}
