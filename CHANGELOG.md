# Changelog

All notable changes to **niagaramcp** are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased] — v0.5 user-context work in progress

User-Context gateway + per-user audit, foundation for write-tools that
mutate the station component tree under the calling user's Niagara
permissions instead of the service identity. Branch
`v0.5-user-context`, accumulating commits.

### Added (so far)

- **User-Context auth**: bearer tokens now resolve to `BUser` (via
  per-user `mcp:tokenHash` tag, salted SHA-256, constant-time
  compare). The legacy `apiToken` continues to authenticate as a
  read-only service identity for monitoring and read tools — write
  tools that require a real user reject it with `-32011`.
- New `BMcpPlatformService.tokenSalt` property (read-only, lazy-
  generated on first start) — base64 16-byte SecureRandom.
- New `auth.UserContextGateway.run(BUser, OpDesc, args, sessionId,
  ContextAwareWork<T>)` — single entry point for write tools.
  Builds `BasicContext(user)`, wraps `PermissionException` into
  `-32010` with rich `data{user, ord, operation, tool}`, emits one
  audit record per call.
- New `audit.*` package: `Audit.install/emit` facade, immutable
  `AuditRecord`, `JsonlAuditWriter` (primary, JSON-line append to
  `<userHome>/niagaramcp/niagaramcp.audit.log`), redactor with
  default key blacklist `(password|secret|token|apikey|passcode|
  pwd|credential)` + 256-char string truncation,
  `BAuditHistoryServiceAdapter` (best-effort secondary, reflection-
  only, no compile-time dep on `history-rt` so lightweight JACE
  installs aren't blocked).
- Tool interface: 2 new default methods — `requiresUserContext()`
  (default `false`) and `annotations()` (default
  `ToolAnnotations.READ_ONLY`). Per MCP 2025-06-18 §6.1, every
  `tools/list` row now carries `annotations:{readOnlyHint,
  destructiveHint, idempotentHint, openWorldHint}` plus
  niagaramcp's `requiresUserContext` extension. Existing 36 tools
  inherit defaults — zero touch.
- Pre-dispatch gate: `tools/call` for any tool with
  `requiresUserContext=true` rejects bearer→service-identity (i.e.
  apiToken) with `-32011 ERR_USER_NOT_FOUND` before the tool body
  runs.
- Auto-promote of JSON-string tool results to MCP
  `result.structuredContent` (per spec §5.4) — purely additive,
  legacy clients continue to read `content[0].text`.
- New tool **`createComponent`** (category `write`, annotations
  `MUTATION`, requiresUserContext): reference write-tool that adds
  a fresh `BComponent` of a given Type as a child of an existing
  parent, under the calling user's permissions. Args:
  `{parentOrd, type, name, nameStrategy?:"fail"|"suffix"}`,
  default strategy `"fail"`. Returns
  `{ord, displayName, requestedName, resolvedName}` in both
  `content[0].text` and `structuredContent`.
- 2 new JSON-RPC error codes:
  - **`-32010` ERR_PERMISSION_DENIED** — wraps
    `javax.baja.security.PermissionException`. `error.data` carries
    `{user, ord, operation, tool, detail}` captured at the call-site
    (the underlying PermissionException only exposes a string
    message, no rich payload).
  - **`-32011` ERR_USER_NOT_FOUND** — Bearer presented but does not
    resolve to any `BUser` via the `mcp:tokenHash` tag walk. Distinct
    from HTTP 401: 401 fires when no Bearer at all, -32011 fires when
    a Bearer authenticated against `apiToken` is used to call a tool
    whose `requiresUserContext()` is true.

### Known follow-ups (v0.5.x / v0.6)

- TagDictionary auto-bootstrap currently checks for an existing
  `mcp:` dictionary registration but does not construct one
  programmatically (would require building a populated
  `BTagInfoList`). Operator can add a `BTagDictionaryFile` under
  `Services/TagDictionaryService` for Workbench TagBrowser
  visibility — token tag write/read works without it.
- JSONL audit log size-rotation deferred to v0.5.x (operator
  logrotate handles it for now).
- Operator-configurable redaction pattern via
  `BMcpPlatformService.auditRedactPattern` deferred to v0.5.x.
- Workbench action `generateUserToken(BString)` and MCP tool
  `rotateMcpToken` deferred to v0.5.x — for v0.5 operators write
  tag values manually using `TokenHasher.main()` to compute the
  hex hash.
- `writePoint` retrofit to `requiresUserContext=true` deferred to
  v0.5.x once operator-side rollout is confirmed (avoids breaking
  existing v0.4-style smoke tests).
- M1 write-tools remaining: `removeComponent`, `setSlot`,
  `invokeAction`, `addExtension`, `linkSlots`/`unlinkSlots`,
  `commitStation` — all follow the `createComponent` reference
  shape, queued for v0.5.1+.
- Backfill `structuredContent` is automatic for any existing tool
  whose `text` payload is already a JSON object — no per-tool
  changes needed; clients see the new field immediately on v0.5.

### Planned

- v0.5: `BPasswordPassword` для `apiToken`; `BAuditService` записи для `writePoint`/`bqlQuery`/walkthrough writers; RBAC по ord-pattern; раздельные read-only / write токены.
- v0.5: schedule-related read/write (BWeekSchedule).
- v0.5: file watcher для knowledge.yaml; runtime-mutable `disabledTools`/`sseEnabled`/`streamableEnabled`.
- v0.5: i18n message bundle (translate user-facing tool descriptions; v0.4 left them in Russian).
- v0.5: per-transport session breakdown in `getDiagnosticDump` and the `sessionCount` Property Sheet field (requires McpSessions to expose typed iteration).
- v0.5+: `niagaramcp-wb` subproject — Workbench file-picker for `knowledgeFilePath`, custom Property Sheet renderer for `sessionCount`/health, and PX widgets for embedding in Niagara views.
- (Streamable HTTP-related deferred items, см. ADR-0001 «Open items»)
  - Server-initiated push messages на `GET /mcp` (сейчас вырожденный stub)
  - Streaming response shape (`text/event-stream` на POST для прогресса tool-calls)
  - OAuth2 resource-server profile (Bearer JWT валидация)

---

## [0.4.1] — 2026-05-10

UX-polish patch release before real-world walkthrough testing. Adds
informational visibility on the Workbench Property Sheet and a
comprehensive feature-dump tool. No breaking changes, no schema
changes, no permissions changes.

### Added

- **4 read-only count properties** on `BMcpPlatformService`
  (flags = 3 → SUMMARY + READONLY):
  - `toolCount` — count of registered Tools (35 → 36 in v0.4.1
    after adding `getFeatureDump`)
  - `resourceCount` — count of registered Resources (6)
  - `promptCount` — count of registered Prompts (7)
  - `sessionCount` — combined active McpSessions (SSE + Streamable;
    refreshed on each create/remove via static notification, no
    polling)
  Properties surface on the Workbench Property Sheet for at-a-glance
  health visibility without opening a tool dialog.
- **`getFeatureDump` tool** (category `diagnostic`) — static feature
  inventory of the running server.
  - Args: `format` = `"text"` (default) | `"json"`.
  - Text output: human-readable banner with tools grouped by
    category, resources split static vs templated, prompts,
    transport flags, knowledge stats, sessions, health, and the
    9 impl-defined JSON-RPC error codes (-32001..-32009) with
    meanings.
  - JSON output: same data structured for programmatic consumption.
  - Counterpart to v0.4 `getDiagnosticDump` (dynamic state) — this
    one is purely the static catalog.
- Smoke client steps 23-24: text + JSON `getFeatureDump` shape
  verification (skip via `--skip-v041`).

### Changed

- `NIAGARAMCP_VERSION` constant bumped from `"0.4.0"` to `"0.4.1"`.
  Surfaces in `getServerInfo`, `getFeatureDump`, and `/health`.

### Investigated, not changed

- `knowledgeFilePath` property type stays `String`. Ran a brief check
  for `BFilePath` / `BAbstractFile` / `BFacets.FILE_BROWSE` on baja
  4.15.3.28 — no clean BSimple type for arbitrary OS-absolute paths
  exists, and the only file-browsing facet (`FIELD_EDITOR`) needs a
  Workbench plugin (out of scope here). Documented in the property
  javadoc so the next agent doesn't repeat the dig. File-picker UX
  is deferred to a future `niagaramcp-wb` subproject.

### Deferred

- Per-transport `sessionCount` split (SSE vs Streamable) → v0.5
  alongside the McpSessions API extension for typed iteration.
- Workbench file-picker for `knowledgeFilePath` → v0.5+ (needs `-wb`
  subproject).

---

## [0.4.0] — 2026-05-10

Operational improvements release. Adds transport toggles, tool
category tags, a combined diagnostic dump, and trims the jar
significantly via removing unused JSON utility classes. No
breaking changes.

### Added

- **Transport toggles** — 2 new properties on `BMcpPlatformService`:
  - `sseEnabled` (boolean, default `true`) — controls
    `/sse` + `/messages` endpoints.
  - `streamableEnabled` (boolean, default `true`) — controls
    `/mcp` (POST/GET/DELETE) endpoints.
  - When disabled, the corresponding endpoint returns
    HTTP 503 + JSON-RPC body with new error code **`-32009`
    ERR_TRANSPORT_DISABLED** + `data{transport: ...}`. `/health`
    is exempt — stays accessible regardless. Existing sessions of
    a disabled transport time out naturally via
    `mcpSessionIdleTimeoutSec`. Restart-required (v0.5 may add
    runtime apply).
- **Tool category tags** — `Tool` interface gains a `default String
  getCategory() { return "general"; }` method; all 34 existing
  tools override with one of 10 categories (`transport-test`,
  `read`, `write`, `walkthrough-read`, `walkthrough-write`,
  `management`, `search`, `history`, `alarms`, `diagnostic`).
  `tools/list` response now includes a `category` field per tool
  for client-side grouping.
- **`getDiagnosticDump` tool** (35th tool, category `diagnostic`)
  — combined snapshot in one round-trip: `server` (version,
  uptime, transports), `sessions` (active count), `knowledge`
  (file path, size, counts), `health` (alarm/history/file), and
  `auditLogTail` (last 20 lines of `knowledge.audit.log`).
- **`serverInfo.transports`** array in `initialize` response —
  reflects currently-enabled transports (informational; clients
  pick by URL).
- **`ToolSchemaHelpers`** utility — reusable helpers for tool
  `inputSchema` JSON construction (`stringParam`, `intParam`,
  `boolParam`, `ordParam`, `objectParam`, `stringArrayParam`,
  `objectSchema(required, props…)`, `emptySchema()`). Used by
  the 7 parameter-less tools; available for future tools.
- **Smoke client** extended (`clients/python/niagaramcp_smoke.py`)
  — 3 new steps (20-22) covering serverInfo.transports, tool
  category tags presence, getDiagnosticDump shape. Total now 22.

### Changed

- `serverInfo.version` in `initialize` response now reflects the
  actual niagaramcp module version (sourced from
  `GetServerInfoTool.NIAGARAMCP_VERSION`, bumped to `"0.4.0"`).
  Was historically hardcoded to `"1.0.0"` since v0.1.0.
- All 11 javadoc comments in `com.niagaramcp.json` translated from
  Russian to English. User-facing `description()` strings in 8
  v0.1-v0.2 tool files (BqlQueryTool, EchoTool, etc.) intentionally
  remain Russian — translating those changes the AI-client view of
  the tools (deferred to v0.5 i18n release).

### Removed

- **12 unused classes from `com.niagaramcp.json`**:
  `CDL`, `Cookie`, `CookieList`, `HTTP`, `HTTPTokener`, `JSONML`,
  `Property`, `Test`, `XML`, `XMLParserConfiguration`, `XMLTokener`,
  `XMLXsiTypeConverter`. Verified none are transitively reachable
  from the 3 classes our server code actually imports
  (`JSONArray`, `JSONObject`, `JSONTokener`). Recovered ~36 KB
  of jar size.

### Fixed

- bajadoc UTF-8 encoding (already mitigated in v0.3.1 via
  `tasks.withType<Javadoc> { options.encoding = "UTF-8" }`); now
  fully resolved in the JSON library by source-level translation.

### Build / size

- Jar: **213 KB** (v0.3.1 baseline 243 KB → −30 KB / −12 %).
  Below the v0.4 brief target ≤ 220 KB; ideal < 200 KB not yet
  reached.
- Class count: 110 → 96 (12 removed JSON files contained 16
  classes; 1 added by ToolSchemaHelpers + GetDiagnosticDumpTool).
- `tools/list` count: 34 → 35.

### Compatibility

- v0.3.1 (and v0.3.0/v0.2.0/v0.1.0) endpoints unchanged.
- All 34 v0.3.1 tools work bit-for-bit; new `getDiagnosticDump`
  is purely additive.
- Default state: both transports enabled, behaviour identical to
  v0.3.1.
- Operators using the deleted JSON helper classes (`XML.toJSONObject`,
  `CDL.parse`, etc.) for their own modules need to either copy
  the classes into their codebase or depend on a separate JSON
  library. None of these were public API niagaramcp committed to.

---

## [0.3.1] — 2026-05-10

Patch release: diagnostic capabilities + sample data + small DX
improvements. No breaking changes; all v0.3.0 endpoints unchanged.

### Added

- **4 diagnostic tools** (registered in `BMcpPlatformService`,
  `tools/list` count: 28 → 32):
  - `getServerInfo` — version, uptimeSeconds, sessionCount,
    knowledgeFile{path,size,equipmentCount,lastModifiedMs,exists},
    transports list, registered tools/resources/prompts.
  - `probeOrd {ord}` — exists/type/displayName/parentOrd/slotCount,
    isControlPoint/isWritable/isAlarmSource flags,
    hasHistoryExt + historyExtCount + historyExtIds. Unresolvable
    ords return `{exists: false}` not error.
  - `checkKnowledgeIntegrity` — iterate every ord in the model,
    resolve via `BOrd.make().get()`; report broken refs by
    equipment/role/standalonePoint with reason.
  - `getServiceHealth` — Niagara service availability
    (alarm/history) + knowledge file/audit log readable+writable +
    sample resource read-back.
- **HTTP `GET /niagaramcp/health` endpoint** — unauthenticated
  monitoring probe (the ONE exception to Bearer-on-everything).
  Returns 200 with status/version/uptimeSeconds/knowledgeFileSize/
  sessionCount/healthyServices, OR 503 if any of: alarm/history
  service missing, knowledge file unreadable, platform service
  disabled. Exposes only counts and per-service ok/missing
  booleans — no station data.
- **3 new properties on `BMcpPlatformService`**:
  - `mcpProtocolVersion` (String, default `""`) — override the MCP
    protocol version advertised in `initialize`. Empty = use
    built-in default `2025-06-18`.
  - `maxHistoryRecordsPerQuery` (int, default `10000`) —
    `ReadHistoryTool` caps user-supplied `limit` at
    `min(MAX_LIMIT, property)`.
  - `disabledTools` (String, comma-separated, default `""`) —
    operator can suppress specific tools at startup. Matching is
    case-insensitive. Restart-required (no runtime reconfig in
    v0.3.1; v0.4 may add).
- **Standardised JSON-RPC error codes** — 8 new niagaramcp-defined
  codes in the JSON-RPC 2.0 implementation-defined band
  (-32001..-32008): `ERR_SESSION_NOT_FOUND`, `ERR_TOOL_NOT_FOUND`,
  `ERR_RESOURCE_NOT_FOUND`, `ERR_KNOWLEDGE_UNREADABLE`,
  `ERR_SCHEMA_VALIDATION`, `ERR_ORD_NOT_RESOLVABLE`,
  `ERR_HISTORY_EXT_MISSING`, `ERR_ALARM_SERVICE_MISSING`. Existing
  dispatch sites (Unknown tool/resource/prompt/method) now emit
  `error.data` JSONObject alongside the message
  (`{toolName|uri|promptName|method}`).
- **`samples/` folder** with `mall-knowledge.yaml` (synthetic
  shopping-mall knowledge fixture, ~300 lines) + `samples/README.md`.
  NOT bundled into the jar — pure documentation/test fixture for
  operator import via the `importKnowledge` tool.
- **Smoke client extended** (`clients/python/niagaramcp_smoke.py`) —
  5 new steps (14-19) covering all v0.3.1 additions plus
  `--skip-v031` flag. Total numbered steps now 19.

### Changed

- `McpProtocol.PROTOCOL_VERSION` constant kept as default fallback,
  but `buildInitializeResult()` now reads from
  `BMcpPlatformService.mcpProtocolVersion()` so operator override
  works.
- `ToolRegistry.register()` now consults `setDisabled()` set; tools
  whose name is in the set are silently skipped.
- `error()` helper signature gains `data` parameter; backward-compat
  wrapper retained for existing call sites.

### Fixed

- bajadoc/javadoc encoding: added `tasks.withType<Javadoc> { options.encoding = "UTF-8" }`
  to `niagaramcp-rt.gradle.kts`. Prevents "unmappable character"
  failures on Windows JDKs whose `file.encoding` is not UTF-8 when
  source files contain Cyrillic (tool descriptions are partially in
  Russian).

### Removed

(none)

### Deprecated

(none)

### Compatibility

- v0.3.0 (and v0.2.0/v0.1.0) endpoints unchanged.
- All 28 v0.3.0 tools continue to work bit-for-bit.
- Default protocol version still `2025-06-18`.
- New `/health` is a NEW endpoint, no existing path overlaps.

---

## [0.3.0] — 2026-05-09

### Added

- **Knowledge layer**: `${niagaraUserHome}/niagaramcp/knowledge.yaml`
  (configurable via `knowledgeFilePath`). Schema per
  `docs/concepts/02-format.md`. Dual-format read (YAML or JSON
  on disk both accepted), YAML default on first write. Atomic
  saves, timestamped backups (count limited via
  `knowledgeBackupCount`, default 5), single-file audit log.
- **Hand-written YAML** in-tree (`com.niagaramcp.server.yaml`) — no
  new third-party deps. Reader sniffs JSON via `{`/`[` and delegates
  to embedded `com.niagaramcp.json`.
- **23 new Tools** (total now 28):
  - Walkthrough read (4): `getOverview`, `inspectComponent`,
    `findComponentsByType`, `getSlots`.
  - Walkthrough write basic (5): `createSpace`/`updateSpace`,
    `createEquipmentType`/`updateEquipmentType`, `createEquipment`.
  - Walkthrough write advanced (5): `updateEquipment`,
    `bulkCreateEquipment`, `assignPointToEquipment`,
    `createStandalonePoint`, `validateKnowledge`.
  - Knowledge management (5): `getKnowledgeSummary`,
    `findUnmappedComponents`, `exportKnowledge`,
    `importKnowledge` (incl. `source='sample'`),
    `reloadKnowledge`.
  - Search (3): `findEquipment`, `findInSpace`, `findPoints`.
  - History (1): `readHistory` via `BHistoryExt` +
    `HistorySpaceConnection.timeQuery`. Optional client-side
    aggregation (avg/min/max/count); 10 s iteration timeout +
    10 000 row cap.
  - Alarms (2): `getActiveAlarms` (open) + `getAlarmHistory`
    (time range). Optional `sourceOrdPrefix` filter.
- **MCP Resources** (capability `resources: {}` advertised):
  - `niagara://overview` (static)
  - `niagara://kinds/catalog` (static)
  - `niagara://equipment/{id}` (template)
  - `niagara://spaces/{id}` (template)
  - `niagara://standalone-points/{id}` (template)
  - `niagara://samples/standard-types` (static, jar-bundled
    sample with 5 generic equipment_types)
- **MCP Prompts** (capability `prompts: {}` advertised), 7 hard-coded:
  - `walkthrough.{new_station,continue,verify_types,apply_pattern}`
  - `query.{equipment_state,zone_comfort,alarm_summary}`
- New `BMcpPlatformService` properties:
  `knowledgeFilePath` (default `""` → user home),
  `knowledgeAutoBackup` (default `true`),
  `knowledgeBackupCount` (default `5`).
- Bundled `sample-knowledge.yaml` resource at jar root (5 generic
  equipment_types: ahu, rooftop, chiller, pump, fcu).
- ADR-0002 (`docs/adr/0002-semantic-layer.md`) formalising the
  v0.3.0 design (knowledge layer, dual-format read, no file
  watcher, no new permissions, atomic write + audit log).

### Changed

- `McpProtocol.handle()` dispatches 5 new methods:
  `resources/list`, `resources/templates/list`, `resources/read`,
  `prompts/list`, `prompts/get`. RpcException-based error path
  shared with tools/*.
- `buildInitializeResult()` capability advertisement extended from
  `{tools: {}}` to `{tools: {}, resources: {}, prompts: {}}`.

### Build

- `niagaramcp-rt.gradle.kts`: added Niagara module dependencies
  `:history-rt`, `:alarm-rt`, `:bql-rt` — required at compile
  time (recon §10 noted transitivity assumption was wrong).
- `sourceSets.main.resources` extended to bundle
  `sample-knowledge.yaml` alongside `WEB-INF/**`.

### Compatibility

- All v0.2.0 endpoints unchanged: SSE (`/sse` + `/messages`),
  Streamable HTTP (`POST/GET/DELETE /mcp`), 5 baseline tools,
  Bearer auth, lazy idle eviction. v0.2 clients work bit-for-bit.

### Known issues (carried from v0.2.0; some now updated)

- `apiToken` plain `String` property (deferred to v0.4).
- No per-ord RBAC (deferred to v0.4).
- No audit-trail through `BAuditService`; the per-action
  `knowledge.audit.log` partially addresses for knowledge-mutation
  tools, but `writePoint`/`bqlQuery` still log only to file (deferred).
- `bcLog` to `System.out`, not `BLog` (deferred).
- Embedded `com.niagaramcp.json` may collide with same-FQN classes
  in MCS-style stations (shading deferred).

---

## [0.2.0] — 2026-05-09

### Added

- **Streamable HTTP MCP transport** (per spec 2025-06-18) на тех же
  серверах рядом с легаси SSE+messages.
  - `POST /niagaramcp/mcp` — JSON-RPC inbound. На `initialize` без
    `Mcp-Session-Id` сервер генерирует id и возвращает в response-header.
    Для последующих вызовов id передаётся обратно как request-header.
  - `GET /niagaramcp/mcp` — server→client SSE-канал. Сейчас вырожденный
    (немедленный close); push-сообщений пока нет, никакой tool их не
    производит.
  - `DELETE /niagaramcp/mcp` — явное закрытие сессии. Идемпотентно.
- `Session` interface как transport-agnostic абстракция; `SseSession`
  и `StreamableSession` реализации.
- `StreamableSession` с **lazy idle eviction**: проверка в момент
  `acquireStreamable()`, без background-сweeper-треда (сохраняет
  «zero new threads» baseline из recon §10.4).
- Property `mcpSessionIdleTimeoutSec` на `BMcpPlatformService`
  (default `1800` = 30 минут).
- ADR-0001 (`docs/adr/0001-streamable-http-transport.md`) фиксирует
  все пять решений до их реализации.

### Changed

- `McpProtocol.PROTOCOL_VERSION` `"2024-11-05"` → `"2025-06-18"` в
  ответе `initialize`. Capability shape (`{tools: {}}`) остаётся
  валидной для tools-only сервера в новой спеке.
- `McpSession` переименован в `SseSession` и теперь
  `implements Session`. Внутренний рефактор; SSE-flow поведенчески
  не изменился.
- `McpProtocol.handle()` сигнатура: параметр `McpSession session` →
  `Session session`. Метод единственно использует
  `session.markInitialized()`, который на интерфейсе.

### Fixed

- README и _SMOKE_TEST: исправлены остаточные ссылки на старые пути
  (`ru/bccontrol/...`) после v0.1.0-rebrand'а; убрана не-историческая
  фраза про BCC.RSA в _SMOKE_TEST §2.

### Compatibility

- **v0.1.0 SSE-клиенты продолжают работать** на `/sse` + `/messages`
  без изменений. Новые клиенты используют `POST/GET/DELETE /mcp` с
  заголовком `Mcp-Session-Id`. Обе модели сосуществуют в одном
  servlet'е.

### Build

- Никаких новых runtime-зависимостей. Тот же набор Niagara-плагинов,
  тот же `compileOnly("javax.servlet:javax.servlet-api:3.1.0")`.
- Тот же dev-cert подписи (`Niagara4Modules`).

### Hardening (carried from v0.1.0)

- Bounded SSE queue (`SseSession.MAX_QUEUE = 1000`) сохранён.
- BQL cursor timeout (`BqlQueryTool.ITERATION_TIMEOUT_MS = 10s`)
  сохранён.

### Known issues (carried from v0.1.0)

- `apiToken` plain `String` property; нет per-ord RBAC; нет audit-trail.
- `bcLog` в `System.out` (не `BLog`/`Logger`).
- См. `_CODE_REVIEW.md` (исторический, описывает архитектуру v0.1.0)
  для деталей.

---

## [0.1.0] — 2026-05-09

### Added

- **`BMcpPlatformService`** — Niagara `BIService`, точка входа модуля. Слоты:
  `enabled`, `showLog`, `status`, `apiToken`, `sseHeartbeatSec`. Action `test`.
- **`McpServlet`** — `UnauthenticatedServlet` с собственной bearer-token аутентификацией.
  - `GET /sse` — Server-Sent Events stream с heartbeat и endpoint advertisement.
  - `POST /messages?sessionId=…` — JSON-RPC requests, ответы push'атся в SSE.
- **`McpProtocol`** — JSON-RPC 2.0 handler. Поддержанные методы:
  `initialize`, `notifications/initialized`, `ping`, `tools/list`, `tools/call`.
- **`McpSession` + `McpSessions`** — управление SSE-сессиями через
  `ConcurrentHashMap` + `LinkedBlockingQueue` + sentinel `__CLOSE__`.
- **`Tool`** — plug-in interface: `name()`, `description()`, `schemaJson()`,
  `call(JSONObject)`.
- **5 tools**:
  - `echo` — диагностика связи.
  - `listChildren` — обход slot-tree, depth 1..5.
  - `readPoint` — чтение `BControlPoint` с метаданными и facets.
  - `writePoint` — запись в `BIWritablePoint` (Numeric/Boolean/String/Enum), priority 1..16.
  - `bqlQuery` — BQL → TSV, лимит 1..1000 строк.
- **`module-permissions.xml`** объявляет `NETWORK_COMMUNICATION` и
  `UNAUTHENTICATED_ACCESS` с `purposeKey` для аудита.
- **`module.palette`** содержит `McpPlatformService` для drag-n-drop из Workbench.
- **Embedded `com.niagaramcp.json.*`** (~110 KB, 26 классов) — JSON parser встроен,
  чтобы модуль не зависел от внешнего niagaramcp-json.
- **Документация**: `README.md`, `_CODE_REVIEW.md`, `_SMOKE_TEST.md`.

### Build

- Gradle сборка через стандартные Niagara-плагины: `niagara-module`,
  `niagara-signing`, `niagara-annotation-processors`, `bajadoc`,
  `niagara-jacoco`, `niagara-home-repositories`.
- Зависимости: `nre`, `baja`, `control-rt`, `web-rt`, `javax.servlet-api 3.1.0`
  (compileOnly из `$niagara_home/bin/ext`).
- `WEB-INF/web.xml` пакуется в корень jar через `sourceSets.main.resources`.
- Подпись стандартным dev-cert Niagara (для production-релиза заменить на
  enterprise-cert).

### Hardening (применено перед v0.1.0)

- **Bounded SSE queue**: `LinkedBlockingQueue` ограничена 1000 сообщений
  на сессию (`McpSession.MAX_QUEUE`). При переполнении сообщение дропается,
  warning пишется через `bcLog` — heap не разрастается из-за медленного
  клиента.
- **`bqlQuery` cursor timeout**: 10 секунд на iteration. При срабатывании
  результат помечен в footer строкой `truncated due to 10s timeout`.
- **Dead code удалён**: `nativesLoaded`, action `test`, метод `doTest`.

### Known issues (остаются для v0.1.0)

- `apiToken` хранится в plain `String`-property (виден в Workbench).
- Любой клиент с токеном получает full station write access (нет per-ord RBAC).
- Embedded `com.niagaramcp.json` может конфликтовать с тем же пакетом из
  внешнего модуля (например, MCS-станций) на уровне ClassLoader.
- `bcLog` пишет в `System.out`, а не в `BLog`/Niagara console — отложено
  до v0.2 (полная observability story).
- Нет audit-trail для `writePoint`/`bqlQuery`.
- Подробности и предлагаемые mitigations — в [`_CODE_REVIEW.md`](./_CODE_REVIEW.md).

[Unreleased]: https://github.com/<owner>/<repo>/compare/v0.4.0...HEAD
[0.4.0]: https://github.com/<owner>/<repo>/compare/v0.3.1...v0.4.0
[0.3.1]: https://github.com/<owner>/<repo>/compare/v0.3.0...v0.3.1
[0.3.0]: https://github.com/<owner>/<repo>/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/<owner>/<repo>/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/<owner>/<repo>/releases/tag/v0.1.0
