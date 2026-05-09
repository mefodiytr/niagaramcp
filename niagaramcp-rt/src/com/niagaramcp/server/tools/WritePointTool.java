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

import javax.baja.control.BIWritablePoint;
import javax.baja.control.enums.BPriorityLevel;
import javax.baja.naming.BOrd;
import javax.baja.status.BStatusBoolean;
import javax.baja.status.BStatusEnum;
import javax.baja.status.BStatusNumeric;
import javax.baja.status.BStatusString;
import javax.baja.status.BStatusValue;
import javax.baja.sys.BDynamicEnum;
import javax.baja.sys.BObject;
import com.niagaramcp.json.JSONObject;

/**
 * Writes a value to a {@link BIWritablePoint} at the requested priority
 * (1..16, default 16). Supports Numeric / Boolean / String / Enum
 * writable points; pass {@code null} to release the priority slot.
 */
public final class WritePointTool implements Tool {

  @Override
  public String name() {
    return "writePoint";
  }

  @Override
  public String description() {
    return "Записать значение в writable-точку станции по её ord. Поддерживает Numeric/Boolean/String/Enum writable-точки. Приоритет 1..16 (по умолчанию 16). Передайте value=null чтобы освободить указанный приоритет.";
  }

  @Override
  public String schemaJson() {
    return "{\"type\":\"object\",\"properties\":{\"ord\":{\"type\":\"string\",\"description\":\"Ord точки, например 'station:|slot:/Logic/Setpoint'\"},\"value\":{\"description\":\"Записываемое значение: число, булево, строка, целое (для enum), либо null для освобождения приоритета\"},\"priority\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":16,\"description\":\"Уровень приоритета 1..16 (по умолчанию 16)\"}},\"required\":[\"ord\",\"value\"]}";
  }

  @Override
  public String call(JSONObject args) throws Exception {
    String ordStr = args.optString("ord", "");
    if (ordStr == null || ordStr.length() == 0) {
      throw new IllegalArgumentException("Параметр 'ord' обязателен");
    }
    if (!args.has("value")) {
      throw new IllegalArgumentException("Параметр 'value' обязателен");
    }

    int priority = 16;
    if (args.has("priority") && !args.isNull("priority")) {
      priority = args.getInt("priority");
      if (priority < 1 || priority > 16) {
        throw new IllegalArgumentException("priority должен быть в диапазоне 1..16");
      }
    }

    BObject obj = BOrd.make(ordStr).get();
    if (!(obj instanceof BIWritablePoint)) {
      throw new IllegalArgumentException("Ord не указывает на writable-точку: " + ordStr);
    }
    BIWritablePoint wp = (BIWritablePoint) obj;
    BStatusValue slot = wp.getInStatusValue(BPriorityLevel.make(priority));

    Object raw = args.isNull("value") ? null : args.get("value");

    if (raw == null) {
      slot.setStatusNull(true);
      return "ord=" + ordStr + " <- null @" + priority;
    }

    slot.setStatusNull(false);

    if (wp instanceof javax.baja.control.BNumericWritable) {
      double v = (raw instanceof Number)
          ? ((Number) raw).doubleValue()
          : Double.parseDouble(raw.toString());
      ((BStatusNumeric) slot).setValue(v);
      return "ord=" + ordStr + " <- " + v + " @" + priority;
    }
    if (wp instanceof javax.baja.control.BBooleanWritable) {
      boolean v;
      if (raw instanceof Boolean) {
        v = ((Boolean) raw).booleanValue();
      } else {
        String s = raw.toString().trim().toLowerCase();
        if ("true".equals(s) || "1".equals(s) || "on".equals(s)) {
          v = true;
        } else if ("false".equals(s) || "0".equals(s) || "off".equals(s)) {
          v = false;
        } else {
          throw new IllegalArgumentException("Невалидное boolean значение: " + raw);
        }
      }
      ((BStatusBoolean) slot).setValue(v);
      return "ord=" + ordStr + " <- " + v + " @" + priority;
    }
    if (wp instanceof javax.baja.control.BEnumWritable) {
      int v = (raw instanceof Number)
          ? ((Number) raw).intValue()
          : Integer.parseInt(raw.toString());
      ((BStatusEnum) slot).setValue(BDynamicEnum.make(v));
      return "ord=" + ordStr + " <- enum(" + v + ") @" + priority;
    }
    if (wp instanceof javax.baja.control.BStringWritable) {
      String v = raw.toString();
      ((BStatusString) slot).setValue(v);
      return "ord=" + ordStr + " <- \"" + v + "\" @" + priority;
    }
    throw new IllegalArgumentException("Неподдерживаемый тип writable-точки: " +
        wp.getClass().getSimpleName());
  }
}
