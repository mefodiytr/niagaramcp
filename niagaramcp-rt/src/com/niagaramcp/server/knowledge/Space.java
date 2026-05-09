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
 * Physical space (building/floor/zone/parking/room). See
 * {@code docs/concepts/02-format.md} §Spaces.
 */
public final class Space {
  public String id;
  public String name;
  public List<String> aliases = new ArrayList<String>();
  public String type;
  public String description;
  public String parent;        // id of parent space, or null
  public Map<String,Object> bounds;

  public Space() {}

  public Space(String id, String name) {
    this.id = id;
    this.name = name;
  }

  /** Build from generic Map<String,Object> (YAML/JSON parser output). */
  @SuppressWarnings("unchecked")
  public static Space fromMap(Map<String,Object> m) {
    Space s = new Space();
    s.id          = (String) m.get("id");
    s.name        = (String) m.get("name");
    s.type        = (String) m.get("type");
    s.description = (String) m.get("description");
    s.parent      = (String) m.get("parent");
    Object al = m.get("aliases");
    if (al instanceof List) {
      for (Object o : (List<Object>) al) s.aliases.add(String.valueOf(o));
    }
    Object b = m.get("bounds");
    if (b instanceof Map) s.bounds = (Map<String,Object>) b;
    return s;
  }

  public Map<String,Object> toMap() {
    Map<String,Object> m = new LinkedHashMap<String,Object>();
    if (id          != null) m.put("id",          id);
    if (name        != null) m.put("name",        name);
    if (!aliases.isEmpty())  m.put("aliases",     new ArrayList<Object>(aliases));
    if (type        != null) m.put("type",        type);
    if (description != null) m.put("description", description);
    if (parent      != null) m.put("parent",      parent);
    if (bounds      != null) m.put("bounds",      bounds);
    return m;
  }
}
