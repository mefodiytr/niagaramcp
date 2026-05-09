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

import javax.baja.control.BControlPoint;
import javax.baja.naming.BOrd;
import javax.baja.status.BStatusValue;
import javax.baja.sys.BFacets;
import javax.baja.sys.BObject;
import com.niagaramcp.json.JSONObject;

/**
 * Returns value, status, type, priority and facets of a {@link BControlPoint}
 * referenced by the supplied {@code ord}.
 */
public final class ReadPointTool implements Tool {

  @Override
  public String name() {
    return "readPoint";
  }

  @Override
  public String description() {
    return "Прочитать текущее значение и метаданные точки станции по её ord. Возвращает JSON с полями ord, displayName, type, value, status, priority, out (исходная строка) и facets (если есть, например units).";
  }

  @Override
  public String schemaJson() {
    return "{\"type\":\"object\",\"properties\":{\"ord\":{\"type\":\"string\",\"description\":\"Ord точки, например 'station:|slot:/Logic/Sensor1'\"}},\"required\":[\"ord\"]}";
  }

  @Override
  public String call(JSONObject args) throws Exception {
    String ordStr = args.optString("ord", "");
    if (ordStr == null || ordStr.length() == 0) {
      throw new IllegalArgumentException("Параметр 'ord' обязателен");
    }
    BObject obj = BOrd.make(ordStr).get();
    if (!(obj instanceof BControlPoint)) {
      throw new IllegalArgumentException("Ord не указывает на BControlPoint: " + ordStr);
    }
    BControlPoint p = (BControlPoint) obj;

    JSONObject r = new JSONObject();
    r.put("ord", ordStr);
    try {
      r.put("displayName", safe(p.getDisplayName(null)));
    } catch (Exception ignored) {
      r.put("displayName", "");
    }
    try {
      r.put("type", p.getType().toString());
    } catch (Exception ignored) {
      r.put("type", "");
    }

    Object outObj = p.get("out");
    String outStr = (outObj == null) ? "" : outObj.toString();
    r.put("out", outStr);

    if (outObj instanceof BStatusValue) {
      BStatusValue sv = (BStatusValue) outObj;
      try {
        Object value = sv.getValueValue();
        r.put("value", (value == null) ? "" : value.toString());
      } catch (Exception e) {
        r.put("value", "");
      }
      try {
        r.put("status", sv.getStatus().toString());
      } catch (Exception e) {
        r.put("status", "");
      }
    }

    int at = outStr.lastIndexOf(" @ ");
    if (at >= 0) {
      r.put("priority", outStr.substring(at + 3).trim());
    }

    try {
      BFacets facets = p.getFacets();
      if (facets != null) {
        JSONObject f = new JSONObject();
        String units = facets.gets("units", "");
        if (units != null && units.length() > 0) f.put("units", units);
        String precision = facets.gets("precision", "");
        if (precision != null && precision.length() > 0) f.put("precision", precision);
        String trueText = facets.gets("trueText", "");
        if (trueText != null && trueText.length() > 0) f.put("trueText", trueText);
        String falseText = facets.gets("falseText", "");
        if (falseText != null && falseText.length() > 0) f.put("falseText", falseText);
        if (f.length() > 0) r.put("facets", f);
      }
    } catch (Exception ignored) {
    }

    return r.toString();
  }

  private static String safe(String s) {
    return (s == null) ? "" : s;
  }
}
