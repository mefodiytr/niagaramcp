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
 * Stand-alone sensor not attached to a specific equipment. See
 * {@code docs/concepts/02-format.md} §Points.
 */
public final class StandalonePoint {
  public String id;
  public String name;
  public List<String> aliases = new ArrayList<String>();
  public String space;
  public String ord;
  public String kind;          // temperature, ppm, etc.
  public String roleInSpace;
  public List<String> notes = new ArrayList<String>();

  public StandalonePoint() {}

  @SuppressWarnings("unchecked")
  public static StandalonePoint fromMap(Map<String,Object> m) {
    StandalonePoint p = new StandalonePoint();
    p.id          = (String) m.get("id");
    p.name        = (String) m.get("name");
    p.space       = (String) m.get("space");
    p.ord         = (String) m.get("ord");
    p.kind        = (String) m.get("kind");
    p.roleInSpace = (String) m.get("role_in_space");
    Object al = m.get("aliases");
    if (al instanceof List) for (Object o : (List<Object>) al) p.aliases.add(String.valueOf(o));
    Object n  = m.get("notes");
    if (n  instanceof List) for (Object o : (List<Object>) n) p.notes.add(String.valueOf(o));
    return p;
  }

  public Map<String,Object> toMap() {
    Map<String,Object> m = new LinkedHashMap<String,Object>();
    if (id          != null) m.put("id",            id);
    if (name        != null) m.put("name",          name);
    if (!aliases.isEmpty())  m.put("aliases",       new ArrayList<Object>(aliases));
    if (space       != null) m.put("space",         space);
    if (ord         != null) m.put("ord",           ord);
    if (kind        != null) m.put("kind",          kind);
    if (roleInSpace != null) m.put("role_in_space", roleInSpace);
    if (!notes.isEmpty())    m.put("notes",         new ArrayList<Object>(notes));
    return m;
  }
}
