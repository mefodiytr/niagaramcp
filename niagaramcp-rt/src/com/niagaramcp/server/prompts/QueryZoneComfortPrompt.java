/*
 * Copyright 2026 niagaramcp contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.niagaramcp.server.prompts;

import com.niagaramcp.json.JSONArray;
import com.niagaramcp.json.JSONObject;
import com.niagaramcp.server.Prompt;

/** {@code query.zone_comfort} — temperature/CO2/humidity in a space. */
public final class QueryZoneComfortPrompt implements Prompt {

  @Override public String name()        { return "query.zone_comfort"; }
  @Override public String description() { return "Comfort metrics (temperature, CO2, humidity) in a given space"; }
  @Override public JSONArray arguments() {
    JSONArray a = new JSONArray();
    a.put(PromptUtil.arg("spaceId", "Space id (e.g. parking-sector-e)", true));
    return a;
  }

  @Override
  public JSONArray render(JSONObject args) {
    String space = args.optString("spaceId", "");
    return PromptUtil.oneUserMsg(
        "Покажи комфорт в зоне '" + space + "'.\n\n" +
        "1. Загрузи niagara://spaces/" + space + " — получишь equipment+sensors.\n" +
        "2. Для каждого standalone-point с kind='temperature' / 'humidity' / 'ppm' (CO2) " +
        "вызови readPoint и собери текущее значение + units.\n" +
        "3. Если есть AHU в зоне — добавь supply_air_temp, return_air_temp.\n" +
        "4. Сведи: средняя температура / диапазон / CO2 / влажность. Отметь точки в фолте."
    );
  }
}
