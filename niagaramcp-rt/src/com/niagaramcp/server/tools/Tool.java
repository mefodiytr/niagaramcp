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

  /**
   * @return {@code true} iff this tool needs a real Niagara user identity
   *         (not the service identity / apiToken). When {@code true}, the
   *         servlet pre-dispatch in {@code McpServlet.handle*} resolves
   *         the bearer to a {@code BUser} via {@code BearerResolver};
   *         on miss, returns {@code -32011 ERR_USER_NOT_FOUND} before
   *         {@link #call(com.niagaramcp.json.JSONObject)} is invoked.
   *
   * <p>Default {@code false} preserves backward compatibility — every
   *         existing read-only / diagnostic tool runs under whatever
   *         identity the bearer presents, including the read-only
   *         service identity backing {@code apiToken}.
   *
   * <p>v0.5 introduces this hook; the first tool to set it {@code true}
   *         is {@code createComponent} (commit 9). Later commits will
   *         retrofit {@code writePoint} once safe to gate it behind
   *         user-Context as well.
   */
  default boolean requiresUserContext() { return false; }

  /**
   * @return MCP-spec tool annotations (readOnly / destructive /
   *         idempotent / openWorldHint) advertised in {@code tools/list}.
   *         Default {@link ToolAnnotations#READ_ONLY} — every existing
   *         tool is read-only or returns its result without modifying
   *         externally-observable state. New write-tools must override
   *         with {@link ToolAnnotations#MUTATION} or
   *         {@link ToolAnnotations#DESTRUCTIVE}.
   */
  default ToolAnnotations annotations() { return ToolAnnotations.READ_ONLY; }
}
