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
package com.niagaramcp.server;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import com.niagaramcp.server.tools.Tool;

/**
 * Insertion-ordered name → {@link Tool} map. Populated in
 * {@code BMcpPlatformService.serviceStarted()} and exposed via
 * {@code BMcpPlatformService.getRegistry()} for the protocol dispatcher.
 */
public final class ToolRegistry {

  private final Map<String, Tool> tools = new LinkedHashMap<>();

  public void register(Tool t) {
    tools.put(t.name(), t);
  }

  public Tool get(String name) {
    return tools.get(name);
  }

  public Collection<Tool> all() {
    return Collections.unmodifiableCollection(tools.values());
  }
}
