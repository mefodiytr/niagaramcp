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

import javax.baja.nre.annotations.NiagaraProperties;
import javax.baja.nre.annotations.NiagaraProperty;
import javax.baja.nre.annotations.NiagaraType;
import javax.baja.sys.BComponent;
import javax.baja.sys.BIService;
import javax.baja.sys.Context;
import javax.baja.sys.Property;
import javax.baja.sys.Sys;
import javax.baja.sys.Type;
import com.niagaramcp.server.tools.BqlQueryTool;
import com.niagaramcp.server.tools.EchoTool;
import com.niagaramcp.server.tools.ListChildrenTool;
import com.niagaramcp.server.tools.ReadPointTool;
import com.niagaramcp.server.tools.Tool;
import com.niagaramcp.server.tools.WritePointTool;

/**
 * Niagara {@link BIService} entry point of the niagaramcp module.
 * Hosts the bearer-token-protected MCP endpoint and registers all built-in
 * {@link com.niagaramcp.server.tools.Tool} implementations on service start.
 */
@NiagaraType
@NiagaraProperties({
  @NiagaraProperty(name = "enabled",         type = "boolean", defaultValue = "true"),
  @NiagaraProperty(name = "showLog",         type = "boolean", defaultValue = "false"),
  @NiagaraProperty(name = "status",          type = "String",  defaultValue = "\"stopped\"", flags = 1),
  @NiagaraProperty(name = "apiToken",        type = "String",  defaultValue = "\"\""),
  @NiagaraProperty(name = "sseHeartbeatSec", type = "int",     defaultValue = "25")
})
public final class BMcpPlatformService extends BComponent implements BIService {

  // ================================================================
  // Properties
  // ================================================================

  public static final Property enabled = newProperty(0, true, null);
  public boolean getEnabled() { return getBoolean(enabled); }
  public void setEnabled(boolean v) { setBoolean(enabled, v, null); }

  public static final Property showLog = newProperty(0, false, null);
  public boolean getShowLog() { return getBoolean(showLog); }
  public void setShowLog(boolean v) { setBoolean(showLog, v, null); }

  public static final Property status = newProperty(1, "stopped", null);
  public String getStatus() { return getString(status); }
  public void setStatus(String v) { setString(status, v, null); }

  public static final Property apiToken = newProperty(0, "", null);
  public String getApiToken() { return getString(apiToken); }
  public void setApiToken(String v) { setString(apiToken, v, null); }

  public static final Property sseHeartbeatSec = newProperty(0, 25, null);
  public int getSseHeartbeatSec() { return getInt(sseHeartbeatSec); }
  public void setSseHeartbeatSec(int v) { setInt(sseHeartbeatSec, v, null); }

  // --- TYPE (ALWAYS last static final) ---
  public static final Type TYPE = Sys.loadType(BMcpPlatformService.class);

  @Override
  public Type getType() { return TYPE; }

  // ================================================================
  // Internal state
  // ================================================================

  private boolean serviceIsRunning = false;
  private static volatile ToolRegistry REGISTRY = null;
  private static volatile BMcpPlatformService INSTANCE = null;

  // ================================================================
  // BIService
  // ================================================================

  private static final Type[] serviceTypes = new Type[] { TYPE };

  @Override
  public Type[] getServiceTypes() { return serviceTypes; }

  @Override
  public void serviceStarted() throws Exception {
    started();
    bcLog("serviceStarted");

    ToolRegistry r = new ToolRegistry();
    r.register((Tool) new EchoTool());
    r.register((Tool) new ListChildrenTool());
    r.register((Tool) new ReadPointTool());
    r.register((Tool) new WritePointTool());
    r.register((Tool) new BqlQueryTool());
    REGISTRY = r;
    INSTANCE = this;

    setStatus(getEnabled() ? "running" : "disabled");
    serviceIsRunning = true;
  }

  @Override
  public void serviceStopped() throws Exception {
    bcLog("serviceStopped");
    McpSessions.closeAll();
    REGISTRY = null;
    INSTANCE = null;
    setStatus("stopped");
    serviceIsRunning = false;
  }

  @Override
  public void changed(Property p, Context cx) {
    super.changed(p, cx);
    if (p == enabled && serviceIsRunning) {
      boolean en = getEnabled();
      setStatus(en ? "running" : "disabled");
      if (!en) {
        McpSessions.closeAll();
      }
    }
  }

  // ================================================================
  // Helpers
  // ================================================================

  public void bcLog(String str) {
    if (getShowLog()) {
      System.out.println("niagaramcp BMcpPlatformService " + str);
    }
  }

  public static boolean isEnabled() {
    BMcpPlatformService s = INSTANCE;
    return (s != null && s.getEnabled());
  }

  public static String apiToken() {
    BMcpPlatformService s = INSTANCE;
    return (s == null) ? "" : s.getApiToken();
  }

  public static int sseHeartbeatSec() {
    BMcpPlatformService s = INSTANCE;
    return (s == null) ? 25 : s.getSseHeartbeatSec();
  }

  public static boolean showLog() {
    BMcpPlatformService s = INSTANCE;
    return (s != null && s.getShowLog());
  }

  public static ToolRegistry getRegistry() {
    return REGISTRY;
  }

  /** @return current singleton service instance, or {@code null} if not started. */
  static BMcpPlatformService instance() {
    return INSTANCE;
  }
}
