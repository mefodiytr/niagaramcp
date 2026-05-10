/*
 * Copyright 2026 niagaramcp contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.niagaramcp.server;

import com.niagaramcp.json.JSONArray;
import com.niagaramcp.json.JSONObject;

/** MCP prompt provider — server-curated message template. */
public interface Prompt {
  String name();
  String description();
  /** @return JSON array of {name, description?, required?} — argument schema. */
  JSONArray arguments();
  /** Render the prompt into a sequence of messages with substituted args. */
  JSONArray render(JSONObject args);
}
