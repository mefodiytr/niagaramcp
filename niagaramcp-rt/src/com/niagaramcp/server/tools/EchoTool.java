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

import com.niagaramcp.json.JSONObject;

/** Diagnostic tool: returns the supplied {@code msg} verbatim. */
public final class EchoTool implements Tool {

  @Override
  public String name() {
    return "echo";
  }
  @Override public String getCategory() { return "transport-test"; }

  @Override
  public String description() {
    return "Вернуть переданное сообщение (для проверки соединения)";
  }

  @Override
  public String schemaJson() {
    return "{\"type\":\"object\",\"properties\":{\"msg\":{\"type\":\"string\",\"description\":\"Сообщение для возврата\"}},\"required\":[\"msg\"]}";
  }

  @Override
  public String call(JSONObject args) {
    return args.getString("msg");
  }
}
