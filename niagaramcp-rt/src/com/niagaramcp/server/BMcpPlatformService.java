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
import com.niagaramcp.server.knowledge.KnowledgeStore;
import com.niagaramcp.server.prompts.QueryAlarmSummaryPrompt;
import com.niagaramcp.server.prompts.QueryEquipmentStatePrompt;
import com.niagaramcp.server.prompts.QueryZoneComfortPrompt;
import com.niagaramcp.server.prompts.WalkthroughApplyPatternPrompt;
import com.niagaramcp.server.prompts.WalkthroughContinuePrompt;
import com.niagaramcp.server.prompts.WalkthroughNewStationPrompt;
import com.niagaramcp.server.prompts.WalkthroughVerifyTypesPrompt;
import com.niagaramcp.server.resources.EquipmentResource;
import com.niagaramcp.server.resources.KindsCatalogResource;
import com.niagaramcp.server.resources.OverviewResource;
import com.niagaramcp.server.resources.SampleKnowledgeResource;
import com.niagaramcp.server.resources.SpaceResource;
import com.niagaramcp.server.resources.StandalonePointResource;
import java.io.File;
import com.niagaramcp.server.tools.AssignPointToEquipmentTool;
import com.niagaramcp.server.tools.BqlQueryTool;
import com.niagaramcp.server.tools.BulkCreateEquipmentTool;
import com.niagaramcp.server.tools.CreateEquipmentTool;
import com.niagaramcp.server.tools.CreateEquipmentTypeTool;
import com.niagaramcp.server.tools.CreateSpaceTool;
import com.niagaramcp.server.tools.CreateStandalonePointTool;
import com.niagaramcp.server.tools.EchoTool;
import com.niagaramcp.server.tools.ExportKnowledgeTool;
import com.niagaramcp.server.tools.FindComponentsByTypeTool;
import com.niagaramcp.server.tools.FindEquipmentTool;
import com.niagaramcp.server.tools.FindInSpaceTool;
import com.niagaramcp.server.tools.FindPointsTool;
import com.niagaramcp.server.tools.FindUnmappedComponentsTool;
import com.niagaramcp.server.tools.GetActiveAlarmsTool;
import com.niagaramcp.server.tools.GetAlarmHistoryTool;
import com.niagaramcp.server.tools.GetKnowledgeSummaryTool;
import com.niagaramcp.server.tools.GetOverviewTool;
import com.niagaramcp.server.tools.GetSlotsTool;
import com.niagaramcp.server.tools.ImportKnowledgeTool;
import com.niagaramcp.server.tools.InspectComponentTool;
import com.niagaramcp.server.tools.ListChildrenTool;
import com.niagaramcp.server.tools.ReadHistoryTool;
import com.niagaramcp.server.tools.ReadPointTool;
import com.niagaramcp.server.tools.ReloadKnowledgeTool;
import com.niagaramcp.server.tools.Tool;
import com.niagaramcp.server.tools.UpdateEquipmentTool;
import com.niagaramcp.server.tools.UpdateEquipmentTypeTool;
import com.niagaramcp.server.tools.UpdateSpaceTool;
import com.niagaramcp.server.tools.ValidateKnowledgeTool;
import com.niagaramcp.server.tools.WritePointTool;

/**
 * Niagara {@link BIService} entry point of the niagaramcp module.
 * Hosts the bearer-token-protected MCP endpoint and registers all built-in
 * {@link com.niagaramcp.server.tools.Tool} implementations on service start.
 */
@NiagaraType
@NiagaraProperties({
  @NiagaraProperty(name = "enabled",                  type = "boolean", defaultValue = "true"),
  @NiagaraProperty(name = "showLog",                  type = "boolean", defaultValue = "false"),
  @NiagaraProperty(name = "status",                   type = "String",  defaultValue = "\"stopped\"", flags = 1),
  @NiagaraProperty(name = "apiToken",                 type = "String",  defaultValue = "\"\""),
  @NiagaraProperty(name = "sseHeartbeatSec",          type = "int",     defaultValue = "25"),
  @NiagaraProperty(name = "mcpSessionIdleTimeoutSec", type = "int",     defaultValue = "1800"),
  @NiagaraProperty(name = "knowledgeFilePath",        type = "String",  defaultValue = "\"\""),
  @NiagaraProperty(name = "knowledgeAutoBackup",      type = "boolean", defaultValue = "true"),
  @NiagaraProperty(name = "knowledgeBackupCount",     type = "int",     defaultValue = "5")
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

  public static final Property mcpSessionIdleTimeoutSec = newProperty(0, 1800, null);
  public int getMcpSessionIdleTimeoutSec() { return getInt(mcpSessionIdleTimeoutSec); }
  public void setMcpSessionIdleTimeoutSec(int v) { setInt(mcpSessionIdleTimeoutSec, v, null); }

  public static final Property knowledgeFilePath = newProperty(0, "", null);
  public String getKnowledgeFilePath() { return getString(knowledgeFilePath); }
  public void setKnowledgeFilePath(String v) { setString(knowledgeFilePath, v, null); }

  public static final Property knowledgeAutoBackup = newProperty(0, true, null);
  public boolean getKnowledgeAutoBackup() { return getBoolean(knowledgeAutoBackup); }
  public void setKnowledgeAutoBackup(boolean v) { setBoolean(knowledgeAutoBackup, v, null); }

  public static final Property knowledgeBackupCount = newProperty(0, 5, null);
  public int getKnowledgeBackupCount() { return getInt(knowledgeBackupCount); }
  public void setKnowledgeBackupCount(int v) { setInt(knowledgeBackupCount, v, null); }

  // --- TYPE (ALWAYS last static final) ---
  public static final Type TYPE = Sys.loadType(BMcpPlatformService.class);

  @Override
  public Type getType() { return TYPE; }

  // ================================================================
  // Internal state
  // ================================================================

  private boolean serviceIsRunning = false;
  private static volatile ToolRegistry REGISTRY = null;
  private static volatile ResourceRegistry RESOURCES = null;
  private static volatile PromptRegistry PROMPTS = null;
  private static volatile KnowledgeStore KNOWLEDGE = null;
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
    // v0.1/0.2 baseline tools
    r.register((Tool) new EchoTool());
    r.register((Tool) new ListChildrenTool());
    r.register((Tool) new ReadPointTool());
    r.register((Tool) new WritePointTool());
    r.register((Tool) new BqlQueryTool());
    // v0.3 walkthrough read tools
    r.register((Tool) new GetOverviewTool());
    r.register((Tool) new InspectComponentTool());
    r.register((Tool) new FindComponentsByTypeTool());
    r.register((Tool) new GetSlotsTool());
    // v0.3 walkthrough write tools — basic
    r.register((Tool) new CreateSpaceTool());
    r.register((Tool) new UpdateSpaceTool());
    r.register((Tool) new CreateEquipmentTypeTool());
    r.register((Tool) new UpdateEquipmentTypeTool());
    r.register((Tool) new CreateEquipmentTool());
    // v0.3 walkthrough write tools — advanced
    r.register((Tool) new UpdateEquipmentTool());
    r.register((Tool) new BulkCreateEquipmentTool());
    r.register((Tool) new AssignPointToEquipmentTool());
    r.register((Tool) new CreateStandalonePointTool());
    r.register((Tool) new ValidateKnowledgeTool());
    // v0.3 knowledge management
    r.register((Tool) new GetKnowledgeSummaryTool());
    r.register((Tool) new FindUnmappedComponentsTool());
    r.register((Tool) new ExportKnowledgeTool());
    r.register((Tool) new ImportKnowledgeTool());
    r.register((Tool) new ReloadKnowledgeTool());
    // v0.3 search via knowledge
    r.register((Tool) new FindEquipmentTool());
    r.register((Tool) new FindInSpaceTool());
    r.register((Tool) new FindPointsTool());
    // v0.3 history
    r.register((Tool) new ReadHistoryTool());
    // v0.3 alarms
    r.register((Tool) new GetActiveAlarmsTool());
    r.register((Tool) new GetAlarmHistoryTool());
    REGISTRY = r;

    // v0.3 — Resources
    ResourceRegistry rr = new ResourceRegistry();
    rr.register(new OverviewResource());
    rr.register(new KindsCatalogResource());
    rr.register(new EquipmentResource());
    rr.register(new SpaceResource());
    rr.register(new StandalonePointResource());
    rr.register(new SampleKnowledgeResource());
    RESOURCES = rr;

    // v0.3 — Prompts
    PromptRegistry pr = new PromptRegistry();
    pr.register(new WalkthroughNewStationPrompt());
    pr.register(new WalkthroughContinuePrompt());
    pr.register(new WalkthroughVerifyTypesPrompt());
    pr.register(new WalkthroughApplyPatternPrompt());
    pr.register(new QueryEquipmentStatePrompt());
    pr.register(new QueryZoneComfortPrompt());
    pr.register(new QueryAlarmSummaryPrompt());
    PROMPTS = pr;

    // Knowledge store — load from configured path (or default).
    KnowledgeStore ks = new KnowledgeStore();
    ks.setFile(resolveKnowledgeFile());
    ks.setAutoBackup(getKnowledgeAutoBackup());
    ks.setBackupCount(getKnowledgeBackupCount());
    try {
      ks.load();
    } catch (Exception e) {
      bcLog("KnowledgeStore.load failed (using empty model): " + e.getMessage());
    }
    KNOWLEDGE = ks;

    INSTANCE = this;
    setStatus(getEnabled() ? "running" : "disabled");
    serviceIsRunning = true;
  }

  @Override
  public void serviceStopped() throws Exception {
    bcLog("serviceStopped");
    McpSessions.closeAll();
    REGISTRY  = null;
    RESOURCES = null;
    PROMPTS   = null;
    KNOWLEDGE = null;
    INSTANCE  = null;
    setStatus("stopped");
    serviceIsRunning = false;
  }

  private File resolveKnowledgeFile() {
    String path = getKnowledgeFilePath();
    if (path != null && path.length() > 0) {
      return new File(path);
    }
    File userHome = Sys.getNiagaraUserHome();
    File dir = new File(userHome, "niagaramcp");
    return new File(dir, "knowledge.yaml");
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

  /** @return Streamable-HTTP session idle timeout in milliseconds. */
  public static long mcpSessionIdleTimeoutMs() {
    BMcpPlatformService s = INSTANCE;
    int sec = (s == null) ? 1800 : s.getMcpSessionIdleTimeoutSec();
    return sec * 1000L;
  }

  public static boolean showLog() {
    BMcpPlatformService s = INSTANCE;
    return (s != null && s.getShowLog());
  }

  public static ToolRegistry getRegistry() {
    return REGISTRY;
  }

  /** @return the singleton KnowledgeStore, or {@code null} if service not started. */
  public static KnowledgeStore getKnowledgeStore() {
    return KNOWLEDGE;
  }

  /** @return the resource registry, or {@code null} if not started. */
  public static ResourceRegistry getResourceRegistry() {
    return RESOURCES;
  }

  /** @return the prompt registry, or {@code null} if not started. */
  public static PromptRegistry getPromptRegistry() {
    return PROMPTS;
  }

  /** @return current singleton service instance, or {@code null} if not started. */
  static BMcpPlatformService instance() {
    return INSTANCE;
  }
}
