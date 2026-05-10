/*
 * Copyright 2026 niagaramcp contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.niagaramcp.server;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/** Static + templated {@link Resource} registry. Insertion-ordered iteration. */
public final class ResourceRegistry {

  private final List<Resource> resources = new ArrayList<Resource>();

  public void register(Resource r) { resources.add(r); }

  public Collection<Resource> all() { return Collections.unmodifiableCollection(resources); }

  /** Resources with a static URI (resources/list payload). */
  public List<Resource> staticResources() {
    List<Resource> out = new ArrayList<Resource>();
    for (Resource r : resources) if (r.uri() != null) out.add(r);
    return out;
  }

  /** Resources that are URI templates (resources/templates/list payload). */
  public List<Resource> templates() {
    List<Resource> out = new ArrayList<Resource>();
    for (Resource r : resources) if (r.uriTemplate() != null) out.add(r);
    return out;
  }

  /** Find a resource that can serve the given concrete URI. */
  public Resource find(String uri) {
    for (Resource r : resources) if (r.matches(uri)) return r;
    return null;
  }
}
