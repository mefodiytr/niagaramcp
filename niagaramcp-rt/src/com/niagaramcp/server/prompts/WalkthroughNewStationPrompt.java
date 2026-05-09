/*
 * Copyright 2026 niagaramcp contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.niagaramcp.server.prompts;

import com.niagaramcp.json.JSONArray;
import com.niagaramcp.json.JSONObject;
import com.niagaramcp.server.Prompt;

/** {@code walkthrough.new_station} — full phase 1-6 walkthrough opener. */
public final class WalkthroughNewStationPrompt implements Prompt {

  @Override public String name()        { return "walkthrough.new_station"; }
  @Override public String description() { return "Run the full new-station walkthrough (phases 1-6 per docs/concepts/03-workflow.md)"; }
  @Override public JSONArray arguments() { return new JSONArray(); }

  @Override
  public JSONArray render(JSONObject args) {
    return PromptUtil.oneUserMsg(
        "Запусти полный walkthrough новой станции.\n\n" +
        "Шаги:\n" +
        "1. Вызови getOverview, listChildren station:|slot:/, чтобы понять структуру.\n" +
        "2. Спроси меня про объект (что это, какие зоны, этажи).\n" +
        "3. Создай spaces через createSpace по моим ответам.\n" +
        "4. Найди типовые компоненты (findComponentsByType BAhuController и подобное).\n" +
        "5. Подтверди типы оборудования и создай equipment_types через createEquipmentType.\n" +
        "6. Размечай equipment по одному через createEquipment, спрашивая где располагается.\n" +
        "7. Для каждого equipment найди key points через getSlots и assignPointToEquipment.\n" +
        "8. По завершении вызови validateKnowledge и доложи warnings.\n\n" +
        "Между шагами всегда подтверждай у меня. Используй паттерны из niagara://kinds/catalog как стартовый набор типов (importKnowledge source='sample' mode='merge')."
    );
  }
}
