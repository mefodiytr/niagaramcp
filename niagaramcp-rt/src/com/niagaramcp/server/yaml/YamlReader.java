/*
 * Copyright 2026 niagaramcp contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.niagaramcp.server.yaml;

import com.niagaramcp.json.JSONArray;
import com.niagaramcp.json.JSONObject;
import com.niagaramcp.json.JSONTokener;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hand-written reader supporting the subset of YAML 1.2 used by the
 * niagaramcp knowledge schema — block-style maps and lists, plain /
 * single-quoted / double-quoted scalars, comments, and the empty
 * flow-style collections {@code []} and {@code &#123;&#125;}.
 *
 * <p>Also accepts JSON input transparently — the first non-blank,
 * non-comment character {@code &#123;} or {@code [} switches to
 * the embedded {@code com.niagaramcp.json} parser.
 *
 * <p>Returns a tree of {@code Map<String,Object>} (insertion-ordered),
 * {@code List<Object>}, {@code String}, {@code Long}, {@code Double},
 * {@code Boolean}, or {@code null} leaves.
 *
 * <p>Out of scope: anchors / aliases / merge keys, multi-line block
 * scalars (|/&gt;), custom tags, complex flow style, multi-document
 * streams.
 */
public final class YamlReader {

  private final String[] lines;
  private int idx;
  private final int total;

  private YamlReader(String text) {
    this.lines = splitLines(text);
    this.total = lines.length;
    this.idx = 0;
  }

  /**
   * Parse the given text. Returns a {@code Map<String,Object>} for a
   * top-level map document, {@code List<Object>} for a top-level
   * list, or {@code null}/scalar for trivial documents.
   */
  public static Object parse(String text) throws YamlException {
    if (text == null) return null;
    String sniffed = stripBomAndLeadingBlank(text);
    if (sniffed.length() == 0) return null;
    char first = sniffed.charAt(0);
    if (first == '{' || first == '[') {
      return parseJson(sniffed);
    }
    YamlReader r = new YamlReader(text);
    r.skipBlankAndComment();
    if (r.idx >= r.total) return null;
    int indent = leadingSpaces(r.lines[r.idx]);
    return r.parseNode(indent);
  }

  // ------------------------------------------------------------------
  // JSON path
  // ------------------------------------------------------------------

  private static Object parseJson(String text) throws YamlException {
    try {
      JSONTokener t = new JSONTokener(text);
      Object root = t.nextValue();
      return jsonToTree(root);
    } catch (Exception e) {
      throw new YamlException("JSON parse failed: " + e.getMessage(), -1, -1, e);
    }
  }

  private static Object jsonToTree(Object v) {
    if (v == null || v == JSONObject.NULL) return null;
    if (v instanceof JSONObject) {
      JSONObject jo = (JSONObject) v;
      Map<String,Object> m = new LinkedHashMap<String,Object>();
      for (String key : jo.keySet()) m.put(key, jsonToTree(jo.get(key)));
      return m;
    }
    if (v instanceof JSONArray) {
      JSONArray ja = (JSONArray) v;
      List<Object> list = new ArrayList<Object>(ja.length());
      for (int i = 0; i < ja.length(); i++) list.add(jsonToTree(ja.get(i)));
      return list;
    }
    if (v instanceof Integer)    return Long.valueOf(((Integer) v).longValue());
    if (v instanceof Long)       return v;
    if (v instanceof Float)      return Double.valueOf(((Float) v).doubleValue());
    if (v instanceof Number)     return Double.valueOf(((Number) v).doubleValue());
    if (v instanceof Boolean)    return v;
    return v.toString();
  }

  // ------------------------------------------------------------------
  // YAML path
  // ------------------------------------------------------------------

  /** Parse the node starting at line {@code idx}, indented at {@code indent} spaces. */
  private Object parseNode(int indent) throws YamlException {
    skipBlankAndComment();
    if (idx >= total) return null;
    String line = lines[idx];
    if (leadingSpaces(line) < indent) return null;
    String body = line.substring(indent);
    if (body.startsWith("- ") || body.equals("-")) {
      return parseList(indent);
    }
    return parseMap(indent);
  }

  private Map<String,Object> parseMap(int indent) throws YamlException {
    Map<String,Object> map = new LinkedHashMap<String,Object>();
    while (true) {
      skipBlankAndComment();
      if (idx >= total) break;
      int li = leadingSpaces(lines[idx]);
      if (li < indent) break;
      if (li > indent) {
        throw new YamlException("Unexpected indent inside map", idx + 1, li);
      }
      String body = lines[idx].substring(indent);
      if (body.startsWith("- ") || body.equals("-")) break;       // not a map line
      int colon = findKeyColon(body);
      if (colon < 0) {
        throw new YamlException("Expected key: in map line: " + body, idx + 1, indent);
      }
      String key = unquote(body.substring(0, colon).trim());
      String rest = body.substring(colon + 1).trim();
      idx++;
      Object val = parseValue(indent, rest);
      map.put(key, val);
    }
    return map;
  }

  private List<Object> parseList(int indent) throws YamlException {
    List<Object> list = new ArrayList<Object>();
    while (true) {
      skipBlankAndComment();
      if (idx >= total) break;
      int li = leadingSpaces(lines[idx]);
      if (li < indent) break;
      if (li > indent) {
        throw new YamlException("Unexpected indent inside list", idx + 1, li);
      }
      String body = lines[idx].substring(indent);
      if (!(body.startsWith("- ") || body.equals("-"))) break;
      String rest = body.equals("-") ? "" : body.substring(2).trim();
      idx++;
      Object val = parseValue(indent + 2, rest);
      list.add(val);
    }
    return list;
  }

  /**
   * Parse the value associated with a `key:` or `-` marker.
   * {@code afterMarker} is the trailing content on the same line
   * (may be empty if the value is on subsequent indented lines).
   * {@code childIndent} is the indent of the parent + 2 spaces.
   */
  private Object parseValue(int childIndent, String afterMarker) throws YamlException {
    if (afterMarker.length() == 0) {
      // value continues on indented lines
      skipBlankAndComment();
      if (idx >= total) return null;
      int li = leadingSpaces(lines[idx]);
      if (li <= childIndent - 2) return null;        // value is null (sibling at outer level)
      return parseNode(li);
    }
    // afterMarker is an inline scalar. Strip trailing comments.
    String trimmed = stripTrailingComment(afterMarker).trim();
    if (trimmed.equals("[]")) return new ArrayList<Object>();
    if (trimmed.equals("{}")) return new LinkedHashMap<String,Object>();
    if (trimmed.startsWith("[") || trimmed.startsWith("{")) {
      // flow-style — defer to JSON parser
      try {
        return jsonToTree(new JSONTokener(trimmed).nextValue());
      } catch (Exception e) {
        throw new YamlException("flow-style parse failed: " + e.getMessage(), idx, -1, e);
      }
    }
    // If value is a key-style "key:" with nothing after on this line,
    // afterMarker would be "" already; here we have a real scalar.
    // But scalars may also be a key for an inline map — disallowed.
    return coerceScalar(trimmed);
  }

  // ------------------------------------------------------------------
  // Scalar handling
  // ------------------------------------------------------------------

  /** Coerce a trimmed scalar token to Long/Double/Boolean/null/String. */
  private static Object coerceScalar(String raw) {
    if (raw.length() == 0) return null;
    char first = raw.charAt(0);
    if (first == '"' || first == '\'') {
      return unquote(raw);                            // explicit string, no coercion
    }
    if (raw.equals("null") || raw.equals("~") || raw.equals("Null") || raw.equals("NULL")) {
      return null;
    }
    if (raw.equals("true")  || raw.equals("True")  || raw.equals("TRUE"))  return Boolean.TRUE;
    if (raw.equals("false") || raw.equals("False") || raw.equals("FALSE")) return Boolean.FALSE;
    // int?
    if (looksLikeInt(raw)) {
      try { return Long.valueOf(Long.parseLong(raw)); } catch (NumberFormatException ignore) {}
    }
    if (looksLikeDouble(raw)) {
      try { return Double.valueOf(Double.parseDouble(raw)); } catch (NumberFormatException ignore) {}
    }
    return raw;
  }

  private static boolean looksLikeInt(String s) {
    int i = 0;
    if (s.charAt(0) == '-' || s.charAt(0) == '+') i = 1;
    if (i >= s.length()) return false;
    for (; i < s.length(); i++) {
      if (!Character.isDigit(s.charAt(i))) return false;
    }
    return true;
  }

  private static boolean looksLikeDouble(String s) {
    int i = 0;
    if (s.charAt(0) == '-' || s.charAt(0) == '+') i = 1;
    boolean dot = false, digit = false, exp = false;
    for (; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c >= '0' && c <= '9') { digit = true; continue; }
      if (c == '.' && !dot && !exp) { dot = true; continue; }
      if ((c == 'e' || c == 'E') && digit && !exp) {
        exp = true;
        if (i + 1 < s.length() && (s.charAt(i+1) == '+' || s.charAt(i+1) == '-')) i++;
        continue;
      }
      return false;
    }
    return digit && (dot || exp);
  }

  /** Strip surrounding "" or '' and unescape minimal sequences. */
  private static String unquote(String s) {
    if (s.length() < 2) return s;
    char first = s.charAt(0);
    char last = s.charAt(s.length() - 1);
    if (first == '"' && last == '"') {
      StringBuilder sb = new StringBuilder(s.length() - 2);
      for (int i = 1; i < s.length() - 1; i++) {
        char c = s.charAt(i);
        if (c == '\\' && i + 1 < s.length() - 1) {
          char n = s.charAt(++i);
          switch (n) {
            case 'n':  sb.append('\n'); break;
            case 't':  sb.append('\t'); break;
            case 'r':  sb.append('\r'); break;
            case '"':  sb.append('"');  break;
            case '\\': sb.append('\\'); break;
            default:   sb.append(n);    break;
          }
        } else sb.append(c);
      }
      return sb.toString();
    }
    if (first == '\'' && last == '\'') {
      StringBuilder sb = new StringBuilder(s.length() - 2);
      for (int i = 1; i < s.length() - 1; i++) {
        char c = s.charAt(i);
        if (c == '\'' && i + 1 < s.length() - 1 && s.charAt(i + 1) == '\'') {
          sb.append('\''); i++;
        } else sb.append(c);
      }
      return sb.toString();
    }
    return s;
  }

  // ------------------------------------------------------------------
  // Line / token helpers
  // ------------------------------------------------------------------

  private void skipBlankAndComment() {
    while (idx < total) {
      String line = lines[idx];
      String trim = line.trim();
      if (trim.length() == 0 || trim.charAt(0) == '#') idx++;
      else break;
    }
  }

  private static int leadingSpaces(String s) {
    int n = 0;
    while (n < s.length() && s.charAt(n) == ' ') n++;
    return n;
  }

  /** Find the first ':' that separates a key from value (skip ones inside quoted strings). */
  private static int findKeyColon(String s) {
    boolean inDq = false, inSq = false;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '"'  && !inSq) inDq = !inDq;
      else if (c == '\'' && !inDq) inSq = !inSq;
      else if (c == ':' && !inDq && !inSq) {
        if (i == s.length() - 1) return i;            // "key:" end-of-line
        char next = s.charAt(i + 1);
        if (next == ' ' || next == '\t') return i;    // "key: value"
      }
    }
    return -1;
  }

  /** Strip trailing ` # comment` from an inline value. Preserve `#` inside quoted strings. */
  private static String stripTrailingComment(String s) {
    boolean inDq = false, inSq = false;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '"'  && !inSq) inDq = !inDq;
      else if (c == '\'' && !inDq) inSq = !inSq;
      else if (c == '#' && !inDq && !inSq && i > 0 && s.charAt(i - 1) == ' ') {
        return s.substring(0, i);
      }
    }
    return s;
  }

  private static String[] splitLines(String text) {
    List<String> out = new ArrayList<String>();
    int start = 0;
    int len = text.length();
    for (int i = 0; i < len; i++) {
      char c = text.charAt(i);
      if (c == '\n') {
        out.add(stripCr(text.substring(start, i)));
        start = i + 1;
      }
    }
    if (start < len) out.add(stripCr(text.substring(start)));
    String[] arr = new String[out.size()];
    return out.toArray(arr);
  }

  private static String stripCr(String s) {
    return (s.length() > 0 && s.charAt(s.length() - 1) == '\r')
        ? s.substring(0, s.length() - 1) : s;
  }

  private static String stripBomAndLeadingBlank(String s) {
    int i = 0;
    if (s.length() > 0 && s.charAt(0) == '﻿') i = 1;
    while (i < s.length()) {
      char c = s.charAt(i);
      if (c == ' ' || c == '\t' || c == '\n' || c == '\r') { i++; continue; }
      if (c == '#') {
        // skip comment line
        while (i < s.length() && s.charAt(i) != '\n') i++;
        continue;
      }
      break;
    }
    return s.substring(i);
  }
}
