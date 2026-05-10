/*
 * Copyright 2026 niagaramcp contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.niagaramcp.server.prompts;

import com.niagaramcp.json.JSONArray;
import com.niagaramcp.json.JSONObject;
import com.niagaramcp.server.Prompt;

/** {@code walkthrough.verify_types} — review and refine equipment_types catalogue. */
public final class WalkthroughVerifyTypesPrompt implements Prompt {

  @Override public String name()        { return "walkthrough.verify_types"; }
  @Override public String description() { return "Walk operator through equipment_types and refine typical_points"; }
  @Override public JSONArray arguments() { return new JSONArray(); }

  @Override
  public JSONArray render(JSONObject args) {
    return PromptUtil.oneUserMsg(
        "Проверим equipment_types.\n\n" +
        "1. Загрузи niagara://kinds/catalog — это полный список типов в knowledge.\n" +
        "2. По каждому типу: спроси меня соответствует ли описание (name, aliases), " +
        "и подтверди typical_points — какие роли точек должны быть у этого типа.\n" +
        "3. Где надо добавить новые роли — используй updateEquipmentType с заполненным " +
        "typical_points (он ЗАМЕНЯЕТ список — собери полный заранее).\n" +
        "4. Если типов мало или они слишком общие — предложи importKnowledge " +
        "source='sample' mode='merge' (стандартный набор из 5 типов)."
    );
  }
}
