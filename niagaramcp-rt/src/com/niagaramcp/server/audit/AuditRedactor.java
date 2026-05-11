/*
 * Copyright 2026 niagaramcp contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.niagaramcp.server.audit;

import com.niagaramcp.json.JSONArray;
import com.niagaramcp.json.JSONObject;

import java.util.Iterator;
import java.util.regex.Pattern;

/**
 * Recursive redactor for {@link JSONObject} args destined for the
 * audit log.
 *
 * <p>Mode of operation: walk every key/value pair. If the <em>key
 * name</em> matches the configured blacklist regex (case-insensitive),
 * the value is replaced with the literal string {@value #REDACTED}.
 * Nested objects and arrays are walked recursively.
 *
 * <p>String values longer than {@link #MAX_VALUE_CHARS} are truncated
 * with a {@code "…+N"} suffix so audit lines stay bounded even when a
 * tool happens to receive a base64 blob or a long YAML import.
 *
 * <p>Default blacklist (matches if any of these appears as a substring
 * in the key name): {@code password, secret, token, apikey, passcode,
 * pwd, credential}. Operator-configurable redaction will land in v0.5.x
 * (a {@code BMcpPlatformService.auditRedactPattern} property).
 */
public final class AuditRedactor {

  static final String REDACTED = "<redacted>";
  static final int    MAX_VALUE_CHARS = 256;

  private static final Pattern DEFAULT_BLACKLIST =
      Pattern.compile(".*(password|secret|token|apikey|passcode|pwd|credential).*",
                      Pattern.CASE_INSENSITIVE);

  private final Pattern blacklist;

  public AuditRedactor() { this(DEFAULT_BLACKLIST); }
  public AuditRedactor(Pattern blacklist) { this.blacklist = blacklist; }

  /** @return a new {@link JSONObject} with redaction + truncation applied. */
  public JSONObject redact(JSONObject in) {
    if (in == null) return null;
    JSONObject out = new JSONObject();
    Iterator<String> keys = in.keys();
    while (keys.hasNext()) {
      String k = keys.next();
      Object v = in.opt(k);
      if (blacklist.matcher(k).matches()) {
        out.put(k, REDACTED);
      } else {
        out.put(k, redactValue(v));
      }
    }
    return out;
  }

  private Object redactValue(Object v) {
    if (v instanceof JSONObject) return redact((JSONObject) v);
    if (v instanceof JSONArray)  return redactArray((JSONArray) v);
    if (v instanceof String)     return truncate((String) v);
    return v;
  }

  private JSONArray redactArray(JSONArray arr) {
    JSONArray out = new JSONArray();
    for (int i = 0; i < arr.length(); i++) {
      out.put(redactValue(arr.opt(i)));
    }
    return out;
  }

  private static String truncate(String s) {
    if (s.length() <= MAX_VALUE_CHARS) return s;
    int over = s.length() - MAX_VALUE_CHARS;
    return s.substring(0, MAX_VALUE_CHARS) + "…+" + over;
  }
}
