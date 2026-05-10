# Changelog

All notable changes to **niagaramcp** are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Planned

- v0.4: `BPasswordPassword` для `apiToken`; `BAuditService` записи для `writePoint`/`bqlQuery`/walkthrough writers; RBAC по ord-pattern; раздельные read-only / write токены.
- v0.4: shaded `com.niagaramcp.json` чтобы избежать classloader-конфликтов с MCS-станциями.
- v0.4: schedule-related read/write (BWeekSchedule).
- v0.4: file watcher для knowledge.yaml (если станет нужен — пока reloadKnowledge).
- v0.4: runtime-mutable `disabledTools` (в v0.3.1 — restart-required).
- (Streamable HTTP-related deferred items, см. ADR-0001 «Open items»)
  - Server-initiated push messages на `GET /mcp` (сейчас вырожденный stub)
  - Streaming response shape (`text/event-stream` на POST для прогресса tool-calls)
  - OAuth2 resource-server profile (Bearer JWT валидация)

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

[Unreleased]: https://github.com/<owner>/<repo>/compare/v0.3.1...HEAD
[0.3.1]: https://github.com/<owner>/<repo>/compare/v0.3.0...v0.3.1
[0.3.0]: https://github.com/<owner>/<repo>/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/<owner>/<repo>/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/<owner>/<repo>/releases/tag/v0.1.0
