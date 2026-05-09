/*
 * Copyright 2026 niagaramcp contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.niagaramcp.server.prompts;

import com.niagaramcp.json.JSONArray;
import com.niagaramcp.json.JSONObject;

/** Helpers for building MCP-spec prompt arguments and message arrays. */
public final class PromptUtil {

  private PromptUtil() {}

  /** Build one argument descriptor: {name, description, required}. */
  public static JSONObject arg(String name, String desc, boolean required) {
    JSONObject a = new JSONObject();
    a.put("name", name);
    if (desc != null) a.put("description", desc);
    a.put("required", required);
    return a;
  }

  /** Build a single text message: {role, content:{type:"text", text}}. */
  public static JSONObject msg(String role, String text) {
    JSONObject content = new JSONObject();
    content.put("type", "text");
    content.put("text", text);
    JSONObject m = new JSONObject();
    m.put("role", role);
    m.put("content", content);
    return m;
  }

  /** Single-message convenience: returns a one-element array. */
  public static JSONArray oneUserMsg(String text) {
    JSONArray arr = new JSONArray();
    arr.put(msg("user", text));
    return arr;
  }
}
