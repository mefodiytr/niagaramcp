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
import com.niagaramcp.server.audit.Audit;
import com.niagaramcp.server.audit.BAuditHistoryServiceAdapter;
import com.niagaramcp.server.audit.JsonlAuditWriter;
import com.niagaramcp.server.auth.McpTags;
import com.niagaramcp.server.auth.TokenHasher;
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
import com.niagaramcp.server.tools.CheckKnowledgeIntegrityTool;
import com.niagaramcp.server.tools.CreateComponentTool;
import com.niagaramcp.server.tools.CreateEquipmentTool;
import com.niagaramcp.server.tools.AddExtensionTool;
import com.niagaramcp.server.tools.CommitStationTool;
import com.niagaramcp.server.tools.InvokeActionTool;
import com.niagaramcp.server.tools.LinkSlotsTool;
import com.niagaramcp.server.tools.RemoveComponentTool;
import com.niagaramcp.server.tools.SetSlotTool;
import com.niagaramcp.server.tools.ClearSlotTool;
import com.niagaramcp.server.tools.SetupTestUserTool;
import com.niagaramcp.server.tools.UnlinkSlotsTool;
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
import com.niagaramcp.server.tools.GetDiagnosticDumpTool;
import com.niagaramcp.server.tools.GetFeatureDumpTool;
import com.niagaramcp.server.tools.GetKnowledgeSummaryTool;
import com.niagaramcp.server.tools.GetOverviewTool;
import com.niagaramcp.server.tools.GetServerInfoTool;
import com.niagaramcp.server.tools.GetServiceHealthTool;
import com.niagaramcp.server.tools.GetSlotsTool;
import com.niagaramcp.server.tools.ImportKnowledgeTool;
import com.niagaramcp.server.tools.InspectComponentTool;
import com.niagaramcp.server.tools.ListChildrenTool;
import com.niagaramcp.server.tools.ProbeOrdTool;
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
  @NiagaraProperty(name = "knowledgeBackupCount",     type = "int",     defaultValue = "5"),
  @NiagaraProperty(name = "mcpProtocolVersion",       type = "String",  defaultValue = "\"\""),
  @NiagaraProperty(name = "maxHistoryRecordsPerQuery",type = "int",     defaultValue = "10000"),
  @NiagaraProperty(name = "disabledTools",            type = "String",  defaultValue = "\"\""),
  @NiagaraProperty(name = "sseEnabled",               type = "boolean", defaultValue = "true"),
  @NiagaraProperty(name = "streamableEnabled",        type = "boolean", defaultValue = "true"),
  // v0.4.1 read-only informational counts (flags=3 = SUMMARY+READONLY)
  @NiagaraProperty(name = "toolCount",                type = "int",     defaultValue = "0", flags = 3),
  @NiagaraProperty(name = "resourceCount",            type = "int",     defaultValue = "0", flags = 3),
  @NiagaraProperty(name = "promptCount",              type = "int",     defaultValue = "0", flags = 3),
  @NiagaraProperty(name = "sessionCount",             type = "int",     defaultValue = "0", flags = 3),
  // v0.5: per-service salt for hashing user MCP tokens stored as
  // mcp:tokenHash tags on BUsers. Generated once on first
  // serviceStarted; persists across restarts via .bog.
  // flags=9 (SUMMARY+READONLY) — visible to operators for diagnostic
  // purposes but never edited by hand (changing the salt invalidates ALL
  // existing user tokens at once, requiring a full rotation).
  // NOTE: this was flags=3, which is READONLY+TRANSIENT, not the intended
  // SUMMARY+READONLY (=9). TRANSIENT excludes the property from .bog, so the
  // salt was silently discarded on every station restart and regenerated —
  // invalidating every bound user token each restart. The comment always said
  // "persists across restarts" and "SUMMARY+READONLY"; only the value was wrong.
  @NiagaraProperty(name = "tokenSalt",                type = "String",  defaultValue = "\"\"", flags = 9),
  // v0.5: gate for the test-only `setupTestUser` tool. Default false.
  // When true, smoke client can bind a tokenHash tag to a pre-created
  // BUser via setupTestUser; flip to false in production.
  @NiagaraProperty(name = "enableTestSetup",          type = "boolean", defaultValue = "false")
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

  /**
   * Absolute filesystem path to the knowledge YAML file. Empty string means
   * "use default location" (see {@link #resolveKnowledgeFile()}).
   *
   * <p>Stays a {@code String} (not a richer file-typed property) by
   * deliberate choice — see v0.4.1 commit 3:
   * <ul>
   *   <li>No {@code BFilePath} BSimple exists in baja (4.15.3.28).
   *   <li>{@code BAbstractFile} models files within an in-station file
   *       space (under {@code local:|file:!}), not arbitrary OS paths.
   *   <li>{@code BFacets} has no {@code FILE_BROWSE} key — only
   *       {@code FIELD_EDITOR} which would require a Workbench plugin
   *       (out of scope for this release).
   * </ul>
   * Workbench file-picker UX is deferred to whenever niagaramcp-wb
   * is added.
   */
  public static final Property knowledgeFilePath = newProperty(0, "", null);
  public String getKnowledgeFilePath() { return getString(knowledgeFilePath); }
  public void setKnowledgeFilePath(String v) { setString(knowledgeFilePath, v, null); }

  public static final Property knowledgeAutoBackup = newProperty(0, true, null);
  public boolean getKnowledgeAutoBackup() { return getBoolean(knowledgeAutoBackup); }
  public void setKnowledgeAutoBackup(boolean v) { setBoolean(knowledgeAutoBackup, v, null); }

  public static final Property knowledgeBackupCount = newProperty(0, 5, null);
  public int getKnowledgeBackupCount() { return getInt(knowledgeBackupCount); }
  public void setKnowledgeBackupCount(int v) { setInt(knowledgeBackupCount, v, null); }

  public static final Property mcpProtocolVersion = newProperty(0, "", null);
  public String getMcpProtocolVersion() { return getString(mcpProtocolVersion); }
  public void setMcpProtocolVersion(String v) { setString(mcpProtocolVersion, v, null); }

  public static final Property maxHistoryRecordsPerQuery = newProperty(0, 10000, null);
  public int getMaxHistoryRecordsPerQuery() { return getInt(maxHistoryRecordsPerQuery); }
  public void setMaxHistoryRecordsPerQuery(int v) { setInt(maxHistoryRecordsPerQuery, v, null); }

  public static final Property disabledTools = newProperty(0, "", null);
  public String getDisabledTools() { return getString(disabledTools); }
  public void setDisabledTools(String v) { setString(disabledTools, v, null); }

  public static final Property sseEnabled = newProperty(0, true, null);
  public boolean getSseEnabled() { return getBoolean(sseEnabled); }
  public void setSseEnabled(boolean v) { setBoolean(sseEnabled, v, null); }

  public static final Property streamableEnabled = newProperty(0, true, null);
  public boolean getStreamableEnabled() { return getBoolean(streamableEnabled); }
  public void setStreamableEnabled(boolean v) { setBoolean(streamableEnabled, v, null); }

  // v0.4.1 — read-only informational counts (flags=3 = SUMMARY+READONLY)
  public static final Property toolCount = newProperty(3, 0, null);
  public int getToolCount() { return getInt(toolCount); }
  public void setToolCount(int v) { setInt(toolCount, v, null); }

  public static final Property resourceCount = newProperty(3, 0, null);
  public int getResourceCount() { return getInt(resourceCount); }
  public void setResourceCount(int v) { setInt(resourceCount, v, null); }

  public static final Property promptCount = newProperty(3, 0, null);
  public int getPromptCount() { return getInt(promptCount); }
  public void setPromptCount(int v) { setInt(promptCount, v, null); }

  public static final Property sessionCount = newProperty(3, 0, null);
  public int getSessionCount() { return getInt(sessionCount); }
  public void setSessionCount(int v) { setInt(sessionCount, v, null); }

  // v0.5 — per-service salt for token hashing. flags=3.
  // Initial value "" → triggers lazy generation in serviceStarted().
  public static final Property tokenSalt = newProperty(9, "", null);
  public String getTokenSalt() { return getString(tokenSalt); }
  public void setTokenSalt(String v) { setString(tokenSalt, v, null); }

  // v0.5 — flag for test-only setupTestUser tool. Default false.
  public static final Property enableTestSetup = newProperty(0, false, null);
  public boolean getEnableTestSetup() { return getBoolean(enableTestSetup); }
  public void setEnableTestSetup(boolean v) { setBoolean(enableTestSetup, v, null); }

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
  private static volatile long SERVICE_START_TIME_MS = 0L;

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
    SERVICE_START_TIME_MS = System.currentTimeMillis();

    // v0.5: lazy-generate per-service salt on first start.
    // Empty -> first ever start (or operator wiped the value); generate fresh.
    //
    // The salt MUST be persisted to .bog the moment it is generated. Niagara
    // does not save the station on shutdown by default, so a salt left only in
    // the in-memory property is lost on the next restart and regenerated —
    // which re-hashes to a different value and silently invalidates every
    // bound mcp:tokenHash on EVERY restart, not just on a module reinstall.
    // (Observed in the field: three different salts on one station in a day,
    // each restart killing all user tokens.) setTokenSalt alone is not enough;
    // save() now, exactly as the knowledge store does for knowledgeData. This
    // save runs at most once per station lifetime (subsequent starts find a
    // non-empty salt and skip both the generate and the save).
    if (getTokenSalt() == null || getTokenSalt().isEmpty()) {
      String fresh = TokenHasher.generateSaltBase64();
      setTokenSalt(fresh);
      bcLog("generated initial tokenSalt (length=" + fresh.length() + ")");
      try {
        Sys.getStation().save();
        bcLog("persisted tokenSalt to station config");
      } catch (Exception e) {
        // Non-fatal: the service still runs and tokens hash correctly this
        // session. But the salt will regenerate on the next restart and
        // invalidate bound user tokens, so surface it loudly.
        bcLog("WARNING: could not persist tokenSalt (" + e + "); bound user "
            + "tokens will break on the next station restart until re-provisioned");
      }
    }

    // v0.5: best-effort TagDictionary bootstrap for the "mcp:" namespace.
    // Token tags work without it — this only affects Workbench TagBrowser
    // visibility. See McpTags javadoc for why we don't auto-construct
    // a BTagDictionary in v0.5.
    boolean tagDictReady = McpTags.attemptDictionaryBootstrap();
    if (!tagDictReady) {
      bcLog("mcp: TagDictionary not registered (token tags still work; " +
            "operator may add a BTagDictionaryFile under " +
            "Services/TagDictionaryService for Workbench TagBrowser visibility — " +
            "see samples/README.md v0.5 section)");
    }

    // v0.5: install audit pipeline. JsonlAuditWriter is primary
    // (always-on, full record). BAuditHistoryServiceAdapter is
    // best-effort secondary — no-op when history-rt isn't installed
    // (lightweight JACE) or the service isn't running.
    File auditFile = new File(resolveKnowledgeFile().getParentFile(), "niagaramcp.audit.log");
    JsonlAuditWriter jsonl = new JsonlAuditWriter(auditFile);
    BAuditHistoryServiceAdapter wbAudit = BAuditHistoryServiceAdapter.install(
        new BAuditHistoryServiceAdapter.WarningSink() {
          public void warn(String msg) {
            bcLog("BAuditHistoryService unavailable (" + msg
                + "); JSONL remains primary at " + auditFile.getAbsolutePath());
          }
        });
    Audit.install(new Audit.CompositeAuditor().add(jsonl).add(wbAudit));

    ToolRegistry r = new ToolRegistry();
    // v0.3.1: skip operator-disabled tools (read directly from this — INSTANCE not set yet)
    r.setDisabled(parseDisabledToolNames(getDisabledTools()));
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
    // v0.3.1 diagnostics
    r.register((Tool) new GetServerInfoTool());
    r.register((Tool) new ProbeOrdTool());
    r.register((Tool) new CheckKnowledgeIntegrityTool());
    r.register((Tool) new GetServiceHealthTool());
    // v0.4 diagnostics
    r.register((Tool) new GetDiagnosticDumpTool());
    // v0.4.1 diagnostics — static feature inventory
    r.register((Tool) new GetFeatureDumpTool());
    // v0.5: first user-Context write tool (reference for the M1 set)
    r.register((Tool) new CreateComponentTool());
    // v0.5: test-only helper for smoke step 25 (gated by enableTestSetup)
    r.register((Tool) new SetupTestUserTool());
    // v0.5.1: M1 write-tools tail
    r.register((Tool) new RemoveComponentTool());
    r.register((Tool) new SetSlotTool());
    r.register((Tool) new InvokeActionTool());
    r.register((Tool) new AddExtensionTool());
    r.register((Tool) new LinkSlotsTool());
    r.register((Tool) new UnlinkSlotsTool());
    r.register((Tool) new CommitStationTool());
    // v0.5.2: write-tool polish
    r.register((Tool) new ClearSlotTool());
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

    // v0.4.1 — populate read-only count properties for Workbench visibility.
    // tool/resource/prompt counts are static after registration; sessionCount
    // is refreshed on every McpSessions create/remove via refreshSessionCount().
    setToolCount(r.all().size());
    setResourceCount(rr.all().size());
    setPromptCount(pr.all().size());
    setSessionCount(McpSessions.activeCount());

    setStatus(getEnabled() ? "running" : "disabled");
    serviceIsRunning = true;
  }

  /**
   * v0.4.1 — refresh the {@code sessionCount} property from
   * {@link McpSessions#activeCount()}. Called by McpSessions on
   * create/remove/closeAll. No-op if the service isn't started.
   * Combined count only (per-transport split deferred to v0.5
   * along with McpSessions API extension).
   */
  public static void refreshSessionCount() {
    BMcpPlatformService s = INSTANCE;
    if (s == null) return;
    try {
      s.setSessionCount(McpSessions.activeCount());
    } catch (Exception ignored) {
      // Setting the property may fail if the service is mid-shutdown;
      // sessionCount becoming briefly stale is harmless.
    }
  }

  @Override
  public void serviceStopped() throws Exception {
    bcLog("serviceStopped");
    McpSessions.closeAll();
    Audit.clear();
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

  /**
   * @return base64-encoded per-service salt for hashing user MCP tokens,
   *         or empty string if the service is not started yet. Salt is
   *         lazy-generated on first {@link #serviceStarted()} and
   *         persists in .bog across restarts.
   */
  public static String tokenSalt() {
    BMcpPlatformService s = INSTANCE;
    return (s == null) ? "" : s.getTokenSalt();
  }

  /** @return whether the test-only {@code setupTestUser} tool is enabled. */
  public static boolean enableTestSetup() {
    BMcpPlatformService s = INSTANCE;
    return (s != null) && s.getEnableTestSetup();
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

  /** @return epoch ms of last serviceStarted; 0 if never started. */
  public static long getServiceStartTimeMs() {
    return SERVICE_START_TIME_MS;
  }

  /**
   * @return configured MCP protocol version, falling back to the default
   *         {@code "2025-06-18"} when the property is empty / instance not started.
   */
  public static String mcpProtocolVersion() {
    BMcpPlatformService s = INSTANCE;
    if (s == null) return "2025-06-18";
    String v = s.getMcpProtocolVersion();
    return (v == null || v.isEmpty()) ? "2025-06-18" : v;
  }

  /** @return configured max history records per query (default 10000). */
  public static int maxHistoryRecordsPerQuery() {
    BMcpPlatformService s = INSTANCE;
    return (s == null) ? 10000 : s.getMaxHistoryRecordsPerQuery();
  }

  /** @return whether the legacy SSE+messages transport is enabled (default true). */
  public static boolean sseEnabled() {
    BMcpPlatformService s = INSTANCE;
    return (s == null) || s.getSseEnabled();
  }

  /** @return whether the Streamable HTTP transport is enabled (default true). */
  public static boolean streamableEnabled() {
    BMcpPlatformService s = INSTANCE;
    return (s == null) || s.getStreamableEnabled();
  }

  /**
   * @return set of disabled tool names from the {@code disabledTools} property
   *         (comma-separated, trimmed, lowercased). Empty set when unset.
   */
  public static java.util.Set<String> disabledToolNames() {
    BMcpPlatformService s = INSTANCE;
    return parseDisabledToolNames(s == null ? "" : s.getDisabledTools());
  }

  /** Parse the raw comma-separated property text into a normalised set. */
  static java.util.Set<String> parseDisabledToolNames(String raw) {
    java.util.Set<String> set = new java.util.HashSet<String>();
    if (raw == null || raw.isEmpty()) return set;
    String[] parts = raw.split(",");
    for (int i = 0; i < parts.length; i++) {
      String t = parts[i].trim();
      if (!t.isEmpty()) set.add(t.toLowerCase(java.util.Locale.ROOT));
    }
    return set;
  }

  /** @return current singleton service instance, or {@code null} if not started. */
  static BMcpPlatformService instance() {
    return INSTANCE;
  }
}
