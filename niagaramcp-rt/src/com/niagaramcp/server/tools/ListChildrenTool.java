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
package com.niagaramcp.server.tools;

import javax.baja.naming.BOrd;
import javax.baja.sys.BComponent;
import javax.baja.sys.BObject;
import com.niagaramcp.json.JSONArray;
import com.niagaramcp.json.JSONObject;

/**
 * Walks the slot-tree rooted at the given ord and returns each node as
 * {@code {name, ord, type, displayName, isPoint, children?}}. Depth is
 * clamped to {@link #MAX_DEPTH} (5).
 */
public final class ListChildrenTool implements Tool {

  private static final int DEFAULT_DEPTH = 1;
  private static final int MAX_DEPTH = 5;

  @Override
  public String name() {
    return "listChildren";
  }

  @Override
  public String description() {
    return "Перечислить дочерние компоненты узла станции по его ord на заданную глубину. Возвращает JSON с полями name, ord, type, displayName, isPoint и (при depth>1) children. Используется для обзора структуры станции без BQL. depth=1 — прямые потомки, максимум 5.";
  }

  @Override
  public String schemaJson() {
    return "{\"type\":\"object\",\"properties\":{\"ord\":{\"type\":\"string\",\"description\":\"Ord узла, например 'station:|slot:/' или 'station:|slot:/Drivers'\"},\"depth\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":5,\"description\":\"Глубина обхода (1..5, по умолчанию 1)\"}},\"required\":[\"ord\"]}";
  }

  @Override
  public String call(JSONObject args) throws Exception {
    String ordStr = args.optString("ord", "");
    if (ordStr == null || ordStr.length() == 0) {
      throw new IllegalArgumentException("Параметр 'ord' обязателен");
    }
    int depth = DEFAULT_DEPTH;
    if (args.has("depth") && !args.isNull("depth")) {
      depth = args.getInt("depth");
      if (depth < 1) depth = 1;
      if (depth > MAX_DEPTH) depth = MAX_DEPTH;
    }

    BObject obj = BOrd.make(ordStr).get();
    if (!(obj instanceof BComponent)) {
      throw new IllegalArgumentException(
          "Ord не указывает на BComponent: " + ordStr +
          " (тип: " + ((obj == null) ? "null" : obj.getClass().getSimpleName()) + ")");
    }
    BComponent root = (BComponent) obj;

    JSONObject result = nodeJson(root, ordStr);
    result.put("children", childrenJson(root, ordStr, depth));
    return result.toString();
  }

  private static JSONArray childrenJson(BComponent parent, String parentOrd, int remainingDepth) {
    JSONArray arr = new JSONArray();
    BComponent[] kids;
    try {
      kids = parent.getChildComponents();
    } catch (Exception e) {
      return arr;
    }
    if (kids == null) return arr;
    for (int i = 0; i < kids.length; i++) {
      BComponent c = kids[i];
      String childName = c.getName();
      String childOrd = joinOrd(parentOrd, childName);
      JSONObject node = nodeJson(c, childOrd);
      if (remainingDepth > 1) {
        node.put("children", childrenJson(c, childOrd, remainingDepth - 1));
      }
      arr.put(node);
    }
    return arr;
  }

  private static JSONObject nodeJson(BComponent c, String ord) {
    JSONObject o = new JSONObject();
    o.put("name", safe(c.getName()));
    o.put("ord", ord);
    try {
      o.put("displayName", safe(c.getDisplayName(null)));
    } catch (Exception e) {
      o.put("displayName", "");
    }
    try {
      o.put("type", c.getType().toString());
    } catch (Exception e) {
      o.put("type", "");
    }
    o.put("isPoint", c instanceof javax.baja.control.BControlPoint);
    return o;
  }

  private static String joinOrd(String parentOrd, String childName) {
    if (parentOrd == null || parentOrd.length() == 0) return childName;
    if (parentOrd.endsWith("/")) return parentOrd + childName;
    return parentOrd + "/" + childName;
  }

  private static String safe(String s) {
    return (s == null) ? "" : s;
  }
}
