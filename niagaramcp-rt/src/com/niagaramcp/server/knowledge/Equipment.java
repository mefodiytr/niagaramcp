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
 * Concrete equipment instance with type, location, key points.
 * See {@code docs/concepts/02-format.md} §Equipment.
 */
public final class Equipment {
  public String id;
  public String name;
  public List<String> aliases = new ArrayList<String>();
  public String type;          // EquipmentType.id
  public String space;         // Space.id, optional
  public String ord;           // Niagara ord, required
  public String description;
  /** role -> ord (Niagara ord). */
  public Map<String,String> points = new LinkedHashMap<String,String>();
  public String schedule;      // optional
  public List<String> notes = new ArrayList<String>();

  public Equipment() {}

  @SuppressWarnings("unchecked")
  public static Equipment fromMap(Map<String,Object> m) {
    Equipment e = new Equipment();
    e.id          = (String) m.get("id");
    e.name        = (String) m.get("name");
    e.type        = (String) m.get("type");
    e.space       = (String) m.get("space");
    e.ord         = (String) m.get("ord");
    e.description = (String) m.get("description");
    e.schedule    = (String) m.get("schedule");
    Object al = m.get("aliases");
    if (al instanceof List) for (Object o : (List<Object>) al) e.aliases.add(String.valueOf(o));
    Object n  = m.get("notes");
    if (n  instanceof List) for (Object o : (List<Object>) n) e.notes.add(String.valueOf(o));
    Object p  = m.get("points");
    if (p  instanceof Map) {
      for (Map.Entry<String,Object> en : ((Map<String,Object>) p).entrySet()) {
        e.points.put(en.getKey(), String.valueOf(en.getValue()));
      }
    }
    return e;
  }

  public Map<String,Object> toMap() {
    Map<String,Object> m = new LinkedHashMap<String,Object>();
    if (id          != null) m.put("id",          id);
    if (name        != null) m.put("name",        name);
    if (!aliases.isEmpty())  m.put("aliases",     new ArrayList<Object>(aliases));
    if (type        != null) m.put("type",        type);
    if (space       != null) m.put("space",       space);
    if (ord         != null) m.put("ord",         ord);
    if (description != null) m.put("description", description);
    if (!points.isEmpty()) {
      Map<String,Object> p = new LinkedHashMap<String,Object>();
      for (Map.Entry<String,String> en : points.entrySet()) p.put(en.getKey(), en.getValue());
      m.put("points", p);
    }
    if (schedule    != null) m.put("schedule",    schedule);
    if (!notes.isEmpty())    m.put("notes",       new ArrayList<Object>(notes));
    return m;
  }
}
