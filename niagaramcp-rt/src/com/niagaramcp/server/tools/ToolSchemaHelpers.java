/*
 * Copyright 2026 niagaramcp contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.niagaramcp.server.tools;

import com.niagaramcp.json.JSONArray;
import com.niagaramcp.json.JSONObject;

/**
 * Reusable helpers for building MCP tool {@code inputSchema} JSON.
 *
 * <p>Most v0.1-v0.3 tools hand-rolled their schema strings
 * (~5-10 lines each of escaped JSON). This helper collapses the
 * common shapes — {@code ord}, plain {@code string}, {@code int},
 * {@code boolean} — into one-liners.
 *
 * <p>Returned {@link JSONObject}s are mutable; callers should not
 * cache them across registrations.
 */
public final class ToolSchemaHelpers {

  private ToolSchemaHelpers() {}

  /** {@code "{type":"string"}} property with description. */
  public static JSONObject stringParam(String description) {
    JSONObject p = new JSONObject();
    p.put("type", "string");
    if (description != null) p.put("description", description);
    return p;
  }

  /** {@code "{type":"integer"}} property with description and bounds. */
  public static JSONObject intParam(String description) {
    JSONObject p = new JSONObject();
    p.put("type", "integer");
    if (description != null) p.put("description", description);
    return p;
  }

  /** {@code "{type":"integer", minimum, maximum}} property. */
  public static JSONObject intParam(String description, int min, int max) {
    JSONObject p = intParam(description);
    p.put("minimum", min);
    p.put("maximum", max);
    return p;
  }

  /** {@code "{type":"boolean"}} property with description. */
  public static JSONObject boolParam(String description) {
    JSONObject p = new JSONObject();
    p.put("type", "boolean");
    if (description != null) p.put("description", description);
    return p;
  }

  /** Pre-baked Niagara ord parameter (string with ord-style description). */
  public static JSONObject ordParam(String description) {
    return stringParam(description);
  }

  /** {@code "{type":"object"}} placeholder (no schema for child fields). */
  public static JSONObject objectParam(String description) {
    JSONObject p = new JSONObject();
    p.put("type", "object");
    if (description != null) p.put("description", description);
    return p;
  }

  /** {@code "{type":"array", items: {type:"string"}}} property. */
  public static JSONObject stringArrayParam(String description) {
    JSONObject p = new JSONObject();
    p.put("type", "array");
    JSONObject items = new JSONObject();
    items.put("type", "string");
    p.put("items", items);
    if (description != null) p.put("description", description);
    return p;
  }

  /**
   * Build an object schema with the given properties and required-list.
   * @param properties pairs of (name, JSONObject schema). Even-indexed args
   *                   are field names, odd-indexed are schemas.
   * @param required   field names that are required (may be empty).
   */
  public static String objectSchema(String[] required, Object... properties) {
    JSONObject root = new JSONObject();
    root.put("type", "object");
    JSONObject props = new JSONObject();
    for (int i = 0; i + 1 < properties.length; i += 2) {
      props.put((String) properties[i], properties[i + 1]);
    }
    root.put("properties", props);
    if (required != null && required.length > 0) {
      JSONArray req = new JSONArray();
      for (int i = 0; i < required.length; i++) req.put(required[i]);
      root.put("required", req);
    }
    return root.toString();
  }

  /**
   * Empty-args schema for parameter-less tools.
   * <p>Method (not a {@code static final} constant) so that the string is
   * embedded only once in the jar — Java compile-time constants are inlined
   * at every reference site, defeating the dedup intent.
   */
  public static String emptySchema() {
    return "{\"type\":\"object\",\"properties\":{}}";
  }
}
