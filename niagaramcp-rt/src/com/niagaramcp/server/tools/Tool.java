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

/**
 * MCP tool plug-in interface. Implementations are registered in
 * {@code BMcpPlatformService.serviceStarted()} and looked up by
 * {@link #name()} when the client invokes {@code tools/call}.
 */
public interface Tool {
  String name();

  String description();

  String schemaJson();

  String call(JSONObject args) throws Exception;

  /**
   * @return tool category for client-side grouping in {@code tools/list}.
   *         Default {@code "general"}; tools should override with one of:
   *         transport-test, read, write, walkthrough-read, walkthrough-write,
   *         management, search, history, alarms, diagnostic.
   */
  default String getCategory() { return "general"; }
}
