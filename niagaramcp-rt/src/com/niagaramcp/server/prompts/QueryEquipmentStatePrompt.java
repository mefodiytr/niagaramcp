/*
 * Copyright 2026 niagaramcp contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.niagaramcp.server.prompts;

import com.niagaramcp.json.JSONArray;
import com.niagaramcp.json.JSONObject;
import com.niagaramcp.server.Prompt;

/** {@code query.equipment_state} — current state of one specific equipment. */
public final class QueryEquipmentStatePrompt implements Prompt {

  @Override public String name()        { return "query.equipment_state"; }
  @Override public String description() { return "Get current state (key point values + active alarms) of a specific equipment by id or alias"; }
  @Override public JSONArray arguments() {
    JSONArray a = new JSONArray();
    a.put(PromptUtil.arg("equipmentId", "Equipment id (e.g. ahu-pa-e-01) OR a human alias to find via findEquipment", true));
    return a;
  }

  @Override
  public JSONArray render(JSONObject args) {
    String eq = args.optString("equipmentId", "");
    return PromptUtil.oneUserMsg(
        "Покажи текущее состояние оборудования: '" + eq + "'.\n\n" +
        "1. Если '" + eq + "' выглядит как id — загрузи niagara://equipment/" + eq + ".\n" +
        "   Иначе вызови findEquipment query='" + eq + "', выбери лучший hit.\n" +
        "2. Для resolved equipment: для каждой роли в points вызови readPoint.\n" +
        "3. Вызови getActiveAlarms sourceOrdPrefix=<equipment.ord>.\n" +
        "4. Сведи результаты в краткий отчёт: ключевые значения + активные тревоги."
    );
  }
}
