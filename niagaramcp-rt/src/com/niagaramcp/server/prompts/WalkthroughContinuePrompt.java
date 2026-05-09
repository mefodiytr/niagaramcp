/*
 * Copyright 2026 niagaramcp contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.niagaramcp.server.prompts;

import com.niagaramcp.json.JSONArray;
import com.niagaramcp.json.JSONObject;
import com.niagaramcp.server.Prompt;

/** {@code walkthrough.continue} — resume walkthrough from where the operator left off. */
public final class WalkthroughContinuePrompt implements Prompt {

  @Override public String name()        { return "walkthrough.continue"; }
  @Override public String description() { return "Resume station walkthrough from current state of knowledge.yaml"; }
  @Override public JSONArray arguments() { return new JSONArray(); }

  @Override
  public JSONArray render(JSONObject args) {
    return PromptUtil.oneUserMsg(
        "Продолжи walkthrough.\n\n" +
        "1. Вызови getKnowledgeSummary, посмотри что уже размечено.\n" +
        "2. Вызови findUnmappedComponents typeName='BControlPoint' — найди что осталось.\n" +
        "3. Спроси меня с какого места продолжать (по space, по типу оборудования, по конкретному unmapped списку).\n" +
        "4. Размечай дальше по тому же паттерну: createEquipment + assignPointToEquipment.\n" +
        "5. По завершении сессии вызови validateKnowledge и доложи."
    );
  }
}
