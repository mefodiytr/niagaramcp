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

import java.util.List;
import java.util.Map;

/**
 * Block-style YAML emitter for the niagaramcp knowledge schema.
 *
 * <p>2-space indent, plain scalars when safe, double-quoted scalars
 * when the value contains characters that would otherwise reparse
 * incorrectly. Collection elements always start on a new line so the
 * output is consistent and diff-friendly.
 *
 * <p>Empty collections are emitted with the flow form ({@code []} or
 * {@code &#123;&#125;}) on the same line as their key.
 */
public final class YamlWriter {

  private static final String INDENT_STEP = "  ";

  private YamlWriter() {}

  /**
   * Serialise the given tree to a YAML string. Tree nodes must be
   * {@code Map}/{@code List}/{@code String}/{@code Number}/
   * {@code Boolean}/{@code null}.
   */
  public static String write(Object root) {
    StringBuilder sb = new StringBuilder(256);
    if (root instanceof Map) {
      writeMap((Map<?, ?>) root, sb, 0);
    } else if (root instanceof List) {
      writeList((List<?>) root, sb, 0);
    } else {
      sb.append(formatScalar(root)).append('\n');
    }
    return sb.toString();
  }

  // ------------------------------------------------------------------

  private static void writeMap(Map<?, ?> map, StringBuilder sb, int depth) {
    if (map.isEmpty()) {
      // top-level empty map => emit "{}" on its own line
      indent(sb, depth);
      sb.append("{}\n");
      return;
    }
    for (Map.Entry<?, ?> e : map.entrySet()) {
      String key = String.valueOf(e.getKey());
      Object val = e.getValue();
      indent(sb, depth);
      sb.append(formatKey(key)).append(':');
      writeValueAfterMarker(val, sb, depth);
    }
  }

  private static void writeList(List<?> list, StringBuilder sb, int depth) {
    if (list.isEmpty()) {
      indent(sb, depth);
      sb.append("[]\n");
      return;
    }
    for (Object item : list) {
      indent(sb, depth);
      sb.append('-');
      writeValueAfterMarker(item, sb, depth);
    }
  }

  /** Emit either ` <scalar>\n` inline, or ` []`/` &#123;&#125;`, or `\n` + nested block. */
  private static void writeValueAfterMarker(Object val, StringBuilder sb, int depth) {
    if (val == null) {
      sb.append(" null\n");
      return;
    }
    if (val instanceof Map) {
      Map<?, ?> m = (Map<?, ?>) val;
      if (m.isEmpty()) { sb.append(" {}\n"); return; }
      sb.append('\n');
      writeMap(m, sb, depth + 1);
      return;
    }
    if (val instanceof List) {
      List<?> l = (List<?>) val;
      if (l.isEmpty()) { sb.append(" []\n"); return; }
      sb.append('\n');
      writeList(l, sb, depth + 1);
      return;
    }
    sb.append(' ').append(formatScalar(val)).append('\n');
  }

  // ------------------------------------------------------------------
  // Scalar / key formatting
  // ------------------------------------------------------------------

  private static String formatKey(String k) {
    return needsQuoting(k) ? quoteDouble(k) : k;
  }

  private static String formatScalar(Object v) {
    if (v == null)              return "null";
    if (v instanceof Boolean)   return v.toString();
    if (v instanceof Number)    return v.toString();
    String s = v.toString();
    return needsQuoting(s) ? quoteDouble(s) : s;
  }

  /**
   * Conservative quoting per recon §4.3:
   * quote when the string is empty, leading/trailing whitespace,
   * looks reserved (true/false/null/yes/no), looks numeric, contains
   * structural YAML characters (`:`, `#`, `[`, `]`, `{`, `}`, `,`,
   * `&`, `*`, `!`, `|`, `>`, `'`, `"`, `%`, `@`, backtick), starts
   * with `-`, `?`, `*`, `&`, `!`, `|`, `>`, `'`, `"`, `%`, `@`, or
   * contains any control character.
   */
  static boolean needsQuoting(String s) {
    if (s == null) return true;
    int len = s.length();
    if (len == 0) return true;
    char first = s.charAt(0);
    if (first == ' ' || first == '\t') return true;
    if (s.charAt(len - 1) == ' ' || s.charAt(len - 1) == '\t') return true;
    String low = s.toLowerCase(java.util.Locale.ROOT);
    if (low.equals("null") || low.equals("~")
        || low.equals("true") || low.equals("false")
        || low.equals("yes")  || low.equals("no")
        || low.equals("on")   || low.equals("off")) return true;
    if (looksNumeric(s)) return true;
    // leading reserved indicators
    switch (first) {
      case '-': case '?': case '*': case '&': case '!':
      case '|': case '>': case '\'': case '"':
      case '%': case '@': case '`':
      case '#': case ',': case '[': case ']':
      case '{': case '}':
        return true;
      default: break;
    }
    // interior structural chars
    for (int i = 0; i < len; i++) {
      char c = s.charAt(i);
      if (c < 0x20) return true;                    // control chars
      if (c == ':' && (i + 1 == len || s.charAt(i + 1) == ' ' || s.charAt(i + 1) == '\t')) return true;
      if (c == '#' && i > 0 && s.charAt(i - 1) == ' ') return true;
    }
    return false;
  }

  private static boolean looksNumeric(String s) {
    if (s.length() == 0) return false;
    int i = 0;
    if (s.charAt(0) == '-' || s.charAt(0) == '+') i = 1;
    if (i >= s.length()) return false;
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
    return digit;
  }

  static String quoteDouble(String s) {
    StringBuilder sb = new StringBuilder(s.length() + 2);
    sb.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '\\': sb.append("\\\\"); break;
        case '"':  sb.append("\\\""); break;
        case '\n': sb.append("\\n");  break;
        case '\r': sb.append("\\r");  break;
        case '\t': sb.append("\\t");  break;
        default:
          if (c < 0x20) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
      }
    }
    sb.append('"');
    return sb.toString();
  }

  private static void indent(StringBuilder sb, int depth) {
    for (int i = 0; i < depth; i++) sb.append(INDENT_STEP);
  }
}
