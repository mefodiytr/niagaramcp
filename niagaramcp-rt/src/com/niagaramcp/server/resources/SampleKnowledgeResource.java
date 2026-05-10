/*
 * Copyright 2026 niagaramcp contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.niagaramcp.server.resources;

import com.niagaramcp.server.Resource;
import com.niagaramcp.server.knowledge.KnowledgeStore;

/**
 * {@code niagara://samples/standard-types} — jar-bundled sample
 * knowledge with a standard equipment-type catalogue (5 types).
 */
public final class SampleKnowledgeResource implements Resource {

  public static final String URI = "niagara://samples/standard-types";

  @Override public String uri()         { return URI; }
  @Override public String uriTemplate() { return null; }
  @Override public String name()        { return "Sample knowledge — standard equipment types"; }
  @Override public String description() { return "5 generic equipment_types (ahu, rooftop, chiller, pump, fcu) for opt-in import via importKnowledge"; }
  @Override public String mimeType()    { return "application/x-yaml"; }
  @Override public boolean matches(String u) { return URI.equals(u); }

  @Override
  public String read(String uri) throws Exception {
    return KnowledgeStore.readSampleResource();
  }
}
