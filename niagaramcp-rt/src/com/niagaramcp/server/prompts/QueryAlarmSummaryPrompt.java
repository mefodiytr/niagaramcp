/*
 * Copyright 2026 niagaramcp contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.niagaramcp.server.prompts;

import com.niagaramcp.json.JSONArray;
import com.niagaramcp.json.JSONObject;
import com.niagaramcp.server.Prompt;

/** {@code query.alarm_summary} — alarm overview for a period. */
public final class QueryAlarmSummaryPrompt implements Prompt {

  @Override public String name()        { return "query.alarm_summary"; }
  @Override public String description() { return "Alarm overview for a period (active + history grouped by source)"; }
  @Override public JSONArray arguments() {
    JSONArray a = new JSONArray();
    a.put(PromptUtil.arg("since", "ISO datetime, e.g. 2026-05-09T00:00:00Z. Default: 24h ago.", false));
    return a;
  }

  @Override
  public JSONArray render(JSONObject args) {
    String since = args.optString("since", "");
    String sinceClause = since.isEmpty() ? "за последние 24 часа" : "с " + since;
    return PromptUtil.oneUserMsg(
        "Сделай обзор тревог " + sinceClause + ".\n\n" +
        "1. Вызови getActiveAlarms — текущие открытые.\n" +
        "2. Вызови getAlarmHistory from='" + (since.isEmpty() ? "<now-24h>" : since) + "'.\n" +
        "3. Сгруппируй по sourceOrd (берём префикс slot:/Drivers/<X>/<Y>/), посчитай " +
        "сколько событий на каждый источник.\n" +
        "4. Выведи топ-10 источников по количеству + список текущих unacked.\n" +
        "5. Где источник матчится с equipment (по equipment.ord prefix) — подставь " +
        "имя/alias из knowledge."
    );
  }
}
