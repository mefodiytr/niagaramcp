/*
 * Copyright 2026 niagaramcp contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.niagaramcp.server;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Insertion-ordered name → {@link Prompt} map. */
public final class PromptRegistry {
  private final Map<String,Prompt> prompts = new LinkedHashMap<String,Prompt>();
  public void register(Prompt p)    { prompts.put(p.name(), p); }
  public Prompt get(String name)    { return prompts.get(name); }
  public Collection<Prompt> all()   { return Collections.unmodifiableCollection(prompts.values()); }
}
