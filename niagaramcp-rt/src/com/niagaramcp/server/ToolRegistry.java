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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import com.niagaramcp.server.tools.Tool;

/**
 * Insertion-ordered name → {@link Tool} map. Populated in
 * {@code BMcpPlatformService.serviceStarted()} and exposed via
 * {@code BMcpPlatformService.getRegistry()} for the protocol dispatcher.
 *
 * <p>Tools whose names appear in the {@code disabled} set
 * (case-insensitive, populated from the {@code disabledTools} property
 * before any registration) are silently skipped on {@link #register(Tool)}.
 * Disabling is restart-required — set the property and restart the service.
 */
public final class ToolRegistry {

  private final Map<String, Tool> tools = new LinkedHashMap<>();
  private final Set<String> disabled = new HashSet<>();

  /**
   * Set the disabled-tool names BEFORE the first {@code register()} call.
   * Names are lowercased on insertion; comparison at register time is
   * lowercased too.
   */
  public void setDisabled(Set<String> names) {
    disabled.clear();
    if (names == null) return;
    for (String n : names) {
      if (n != null) disabled.add(n.toLowerCase(Locale.ROOT));
    }
  }

  /** @return immutable view of currently disabled tool names. */
  public Set<String> getDisabled() {
    return Collections.unmodifiableSet(disabled);
  }

  public void register(Tool t) {
    if (t == null || t.name() == null) return;
    if (disabled.contains(t.name().toLowerCase(Locale.ROOT))) return;
    tools.put(t.name(), t);
  }

  public Tool get(String name) {
    return tools.get(name);
  }

  public Collection<Tool> all() {
    return Collections.unmodifiableCollection(tools.values());
  }
}
