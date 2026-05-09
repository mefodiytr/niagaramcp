/*
 * Copyright 2026 niagaramcp contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.niagaramcp.server.prompts;

import com.niagaramcp.json.JSONArray;
import com.niagaramcp.json.JSONObject;
import com.niagaramcp.server.Prompt;

/** {@code walkthrough.apply_pattern} — import a knowledge.yaml from another station. */
public final class WalkthroughApplyPatternPrompt implements Prompt {

  @Override public String name()        { return "walkthrough.apply_pattern"; }
  @Override public String description() { return "Import a knowledge YAML/JSON pasted by the operator (donor station) and merge into current"; }
  @Override public JSONArray arguments() {
    JSONArray a = new JSONArray();
    a.put(PromptUtil.arg("mode", "'merge' (skip id collisions) or 'replace' (wipe current). Default: merge.", false));
    return a;
  }

  @Override
  public JSONArray render(JSONObject args) {
    String mode = args.optString("mode", "merge");
    return PromptUtil.oneUserMsg(
        "Применим knowledge с другого объекта (mode='" + mode + "').\n\n" +
        "1. Попроси оператора вставить YAML/JSON содержимое donor knowledge.\n" +
        "2. Передай его в importKnowledge content=<вставленный текст> mode='" + mode + "'.\n" +
        "3. Покажи итог: сколько добавлено / пропущено по каждой секции.\n" +
        "4. Подскажи: после import обычно нужно поправить equipment.ord — ord'ы " +
        "donor'а могут не совпадать с этой станцией. Запусти findUnmappedComponents " +
        "чтобы увидеть что не привязано."
    );
  }
}
