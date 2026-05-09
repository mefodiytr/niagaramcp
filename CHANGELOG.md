# Changelog

All notable changes to **niagaramcp** are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Planned

- v0.3: `BPasswordPassword` для `apiToken`; `BAuditService` записи для `writePoint`/`bqlQuery`; RBAC по ord-pattern; раздельные read-only / write токены.
- v0.4: shaded `com.niagaramcp.json` чтобы избежать classloader-конфликтов с MCS-станциями.
- (Streamable HTTP-related deferred items, см. ADR-0001 «Open items»)
  - Server-initiated push messages на `GET /mcp` (сейчас вырожденный stub)
  - Streaming response shape (`text/event-stream` на POST для прогресса tool-calls)
  - OAuth2 resource-server profile (Bearer JWT валидация)

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

[Unreleased]: https://github.com/<owner>/<repo>/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/<owner>/<repo>/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/<owner>/<repo>/releases/tag/v0.1.0
