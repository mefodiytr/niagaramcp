# Session notes: v0.2.0 → v0.4 release work

**Версии:** v0.1.0 → v0.2.0 → v0.3.0 → v0.3.1 → v0.4.0
**Время:** несколько сессий, май 2026.

---

## v0.4.0 — Operational improvements (2026-05-10)

**Branch:** `v0.4-operational` (off `v0.3.1-diagnostics` tip — v0.3.1
не была затегана/смержена, поэтому v0.4 продолжает stack).
**Commits:** 8 атомарных. **LOC:** +805 / −2 423 = **−1 618 net**
(commit 3 убрал 12 неиспользуемых JSON utility-классов).
**Jar:** 249 KB → **211.6 KB** (−13 %, target ≤220 KB ✓).

### Что добавлено

- 2 свойства: `sseEnabled` / `streamableEnabled` — операторские
  toggles на транспорты. Disabled → HTTP 503 + JSON-RPC -32009.
  `/health` всегда доступен.
- Tool category tags — все 35 tools имеют `category` (10
  категорий); `tools/list` отдаёт field per tool для UI-группировки.
- 35-й tool `getDiagnosticDump` — combined snapshot (server + sessions
  + knowledge + health + auditLogTail) в один round-trip.
- `serverInfo.transports` массив в `initialize`-ответе.
- `serverInfo.version` теперь корректно отражает версию модуля
  (`"0.4.0"` вместо исторического `"1.0.0"`).
- ToolSchemaHelpers utility class.
- Smoke client +3 шага (steps 20-22), всего 22.

### Что убрано

- 12 неиспользуемых JSON utility-классов
  (XML/CDL/Cookie/HTTP/Property/Test/JSONML/etc.) из
  `com.niagaramcp.json`. Сэкономлено ~36 KB jar.
- 11 файлов JSON library: javadoc-комментарии переведены RU→EN.

### Что отложено в v0.5

- Runtime-mutable transport toggles (без restart).
- Per-transport session split в getDiagnosticDump (требует API
  расширения на McpSessions).
- i18n message bundle (Russian descriptions в tool API).
- Дальнейший json shading (rename в com.niagaramcp.embedded.json).
- BPasswordPassword, BAuditService, RBAC.
- Schedules read/write.

### Branch state

- `v0.4-operational` — unmerged.
- `main` — at v0.3.0 merge + recon.
- v0.3.1 tag не существует (v0.3.1-diagnostics тоже unmerged).
- Stack: main → v0.3.1-diagnostics (8 commits) → v0.4-operational
  (8 commits).

---

## v0.2.0 + v0.3.0 release work (2026-05-09)

**Дата:** 2026-05-09
**Версии:** v0.1.0 → v0.2.0 → v0.3.0
**Общий объём:** ~5 365 LOC + 2 115 LOC recon docs + ~840 LOC concept docs
**Время:** одна продлённая сессия

---

## Контекст до сессии

Модуль niagaramcp на v0.1.0 — базовый MCP-сервер на Niagara
Framework. Поддерживает только legacy SSE+messages транспорт (deprecated в MCP-спеке за 2025-03-26). Имеет 5 базовых tools (echo,
listChildren, readPoint, writePoint, bqlQuery), Bearer auth,
Streamable HTTP отсутствует, semantic layer отсутствует.

Цели сессии:
1. Реализовать Streamable HTTP транспорт per MCP spec 2025-06-18
   (v0.2.0)
2. Добавить semantic enrichment layer для bootstrap'а AI-понимания
   станции (v0.3.0)

---

## v0.2.0 — Streamable HTTP transport

### Подход
Recon-driven development: сначала static inspection кодовой базы
для проверки предположений, потом implementation на их основе.

- `docs/recon-2026-05-09.md` (1 411 строк) — read-only inventory
  существующего кода, validation предположений
- `docs/agent-briefs/v0.2.0-streamable-http-prompt.md` (590 строк) —
  brief для Claude Code на основе recon'а
- 7 atomic commits на `v0.2.0-streamable-http` ветке
- Smoke test через ручной Python script (`clients/python/niagaramcp_smoke.py`)
- Manual verification на live station, потом merge + tag + GitHub release

### Что сделано
- Новый класс `Session` interface — обобщение поверх старого
  `McpSession` и нового `StreamableSession`
- Переименование `McpSession.java` → `SseSession.java` для ясности
- `StreamableSession.java` (86 LOC) — реализация streamable транспорта
  с lazy idle eviction (без новых тредов)
- `McpProtocol.handle()` принимает `Session` вместо `McpSession` —
  единственное изменение, остальное транспорт-агностично
- 5 dispatch branches в `McpServlet.java`: GET /mcp, POST /mcp,
  DELETE /mcp + сохранение SSE+messages flow
- `mcpSessionIdleTimeoutSec` property (default 1800)
- Capability advertisement: protocolVersion bumped to 2025-06-18
- ADR-0001 — Streamable HTTP transport design

### Размер
- 12 files changed, +897/-72 lines
- jar 117 550 → 120 523 bytes (+2 973, в пределах "v0.1.0 ±5 KB
  + ~15 KB" envelope из брифа)
- 290 LOC новых Java vs 250 LOC оценки в recon (+16 %)

### Recon predictions hold rate: 100 %
Все ключевые предсказания recon §13 подтвердились:
- `McpProtocol.handle()` транспорт-агностичен
- Auth выше транспорта (новые endpoints наследуют checkAuth для free)
- Tools unchanged
- Java 8 baseline preserved
- Никаких новых threads, deps, permissions

### Verification
9 шагов в `docs/v0.2.0-implementation-notes.md`. Покрытие: deploy
+ load + curl runbook + SSE backward compat + idle eviction
+ DELETE idempotency + cross-transport isolation + concurrency + auth.
Python smoke client автоматизирует 8 из 9 (idle eviction остаётся
manual т.к. требует короткий timeout config).

---

## v0.3.0 — Semantic Enrichment Layer

### Концепция
4 концепт-документа в `docs/concepts/`:
- `01-concept.md` — AI walkthrough как способ bootstrap'а
  semantic model. Решает chicken-and-egg: AI помогает построить
  модель, которой AI потом пользуется.
- `02-format.md` — schema knowledge.yaml: spaces, equipment_types,
  equipment, points, schedules. EquipmentType может extend другой
  тип (rooftop extends ahu).
- `03-workflow.md` — 6 фаз walkthrough'а. AI инициирует, оператор
  подтверждает. Применение patterns для serial naming. Time budget
  30 мин - 2 часа на типовой объект.
- `04-roadmap.md` — v0.3.0 scope: knowledge layer + walkthrough
  tools + MCP Resources + History tools + Alarms tools.

### Подход
Тот же recon-driven workflow, что и v0.2.0:
- Два recon-файла (split для избежания API timeout):
  `docs/recon-2026-05-13-niagara-apis.md` (920 LOC) и
  `docs/recon-2026-05-13-mcp-and-plan.md` (1 195 LOC)
- 13 atomic commits на `v0.3.0-semantic-layer` ветке (commit 2
  разбит на 2a/2b для изоляции YAML emitter'а)
- Verification через extended Python smoke client (15 шагов)
- Manual verification на live station, потом merge + tag + GitHub
  release

### Что сделано

**Knowledge layer:**
- Custom YAML emitter+reader (~330 LOC code, 649 LOC с javadoc) —
  без snake-yaml dependency, но dual-format read tolerant к JSON
- `KnowledgeStore` — atomic file ops, lock(), backup rotation,
  schema validation
- `KnowledgeModel` + 5 immutable POJOs (Space, EquipmentType,
  Equipment, Point) — Java 8 без records
- `BMcpPlatformService` wired with 3 new properties:
  `knowledgeFilePath`, `knowledgeAutoBackup`, `knowledgeBackupCount`
- knowledge.yaml авто-создаётся в `${niagaraUserHome}/niagaramcp/`
- Sample `standard-types.yaml` в jar (5 generic equipment types),
  доступен через resource `niagara://samples/standard-types`

**25 новых tools:**
- 4 walkthrough read: `getOverview`, `inspectComponent`,
  `findComponentsByType`, `getSlots`
- 5 walkthrough write basic: `createSpace`, `updateSpace`,
  `createEquipmentType`, `updateEquipmentType`, `createEquipment`
- 5 walkthrough write advanced: `updateEquipment`,
  `bulkCreateEquipment`, `assignPointToEquipment`,
  `createStandalonePoint`, `validateKnowledge`
- 5 management: `getKnowledgeSummary`,
  `findUnmappedComponents`, `exportKnowledge`,
  `importKnowledge`, `reloadKnowledge`
- 3 search: `findEquipment`, `findInSpace`, `findPoints`
- 1 history: `readHistory` (через `HistorySpaceConnection.timeQuery`)
- 2 alarms: `getActiveAlarms`, `getAlarmHistory` (через
  `AlarmDbConnection.getOpenAlarms` / `timeQuery` / `bqlQuery`)

**MCP Resources + Prompts:**
- 3 static resources: `niagara://overview`, `niagara://kinds/catalog`,
  `niagara://samples/standard-types`
- 3 templated resources: `niagara://equipment/{type}/{id}`,
  `niagara://space/{path}`, `niagara://standalone-point/{id}`
- 7 prompts: 4 walkthrough (`new_station`, `continue`,
  `verify_types`, `apply_pattern`) + 3 query (`equipment_state`,
  `zone_comfort`, `alarm_summary`)
- 5 new dispatch branches в `McpProtocol.handle()`:
  `resources/list`, `resources/read`, `resources/templates/list`,
  `prompts/list`, `prompts/get`
- Capability advertisement extended: `tools` + `resources` + `prompts`

**ADR-0002** — Semantic enrichment layer design (Status, Context,
Decision, Consequences, Alternatives).

### Размер
- 62 files changed, +5 075/-8 lines
- 38 new Java files в 4 новых packages: `yaml/`, `knowledge/`,
  `resources/`, `prompts/`
- jar 120 523 → 236 107 bytes (+115 584, +96 %)
- 30 tools всего (5 baseline + 25 new)

### Recon predictions hold rate: ~95 %
Recon errors caught на compile (зафикшены в тех же commit'ах):
1. `HistorySpaceConnection.timeQuery()` returns `BITable<BHistoryRecord>`
   not `HistoryCursor` (recon §1.3 ошибся)
2. `BHistoryService.getHistoryDb()` — actual `getDatabase()`
3. `BAlarmRecord.getSource()` returns `BOrdList`, не `BAlarmSource`

Плюс 3 gradle deps оказались не transitive (`:history-rt`,
`:alarm-rt`, `:bql-rt`) — recon §6.7 надеялся на transitive, но
пришлось добавить explicit.

### Constraint compliance: 100 %
- ✓ Java 8 baseline (no var, lambdas-in-interfaces, switch
  expressions, records)
- ✓ No new threads (zero-thread baseline preserved per ADR-0001
  precedent)
- ✓ No new third-party deps (YAML hand-written)
- ✓ No module-permissions.xml edits
- ✓ Backward compat: все v0.2.0 endpoints работают (15-step smoke
  test зелёный)

### Verification
13 шагов в `docs/v0.3.0-implementation-notes.md`. Покрытие через
extended Python smoke client (15 шагов: 8 v0.2.0 + 7 v0.3.0,
включая resources/list, resources/read, prompts/list,
getKnowledgeSummary). Остаётся manual: audit log, backup rotation,
readHistory против реальной точки с history extension,
getActiveAlarms против реальной DB, importKnowledge sample,
reloadKnowledge round-trip.

---

## Workflow patterns установившиеся за сессию

### Паттерн 1: Recon → Brief → Implementation → Notes

Каждая major version — single iteration:
1. **Recon** — read-only inventory + validation предположений.
   Output: `docs/recon-YYYY-MM-DD.md`. Не модифицирует код.
2. **Brief archive** — recon prompt архивируется в
   `docs/agent-briefs/`. Implementation prompt тоже туда.
3. **Implementation** — на отдельной feature branch, atomic
   commits, каждый green-buildable. Branch не мерджится автоматом.
4. **Notes** — `docs/vX.Y.Z-implementation-notes.md` пишется в
   последнем commit'е на ветке: surprises, recon errors,
   verification needed checklist.
5. **Verification** — manual + automated через Python smoke client.
6. **Merge + tag + release** — после прохождения verification
   человеком.

### Паттерн 2: Atomic commits with green build per step

Каждый коммит на feature branch собирается зелёно в isolation
через `./gradlew :niagaramcp-rt:assemble --configure-on-demand`.
Если коммит ломает build — fix в том же commit'е, не "fix in next".

### Паттерн 3: Backward compat absolute

Каждая major version обязана сохранять предыдущую функциональность
бит-в-бит. v0.2.0 не сломал v0.1.0 SSE flow. v0.3.0 не сломал
v0.2.0 Streamable. Поэтому все 5 baseline tools всё ещё в списке,
все predыдущие endpoints отвечают как раньше.

### Паттерн 4: Hard constraints вынесены в brief

Каждый implementation brief начинается с раздела Hard Constraints
(Java 8, no threads, no deps, no permissions, no merge). Это
эффективнее post-hoc review — Claude Code не пытается их
нарушить и не предлагает трюки которые их обходят.

### Паттерн 5: Smoke client как living regression test

Один Python script (`clients/python/niagaramcp_smoke.py`),
stdlib-only, растёт с каждой версией. v0.2.0 → 8 шагов,
v0.3.0 → 15 шагов. Запускается за 5 секунд. Каждый push на main
должен пройти его без regression — это гарантия backward compat.

---

## Открытые вопросы / candidates на будущие версии

### Конфигурация
- `mcpProtocolVersion` property вместо hardcoded
- `maxHistoryRecordsPerQuery` property (default 10000)
- `defaultHistoryAggregation` property
- `auditLogPath` независимый от knowledgeFilePath
- `auditLogMaxSizeBytes` + rotation
- `tools.disabled` list — selective disable
- `resources.cacheTtlSec`
- `logLevel` enum vs `showLog` boolean

### UX/DX
- `getServerInfo` tool — quick capabilities/version без initialize
- Health check endpoint без auth (`/niagaramcp/health`)
- Spy view — live session counts, audit log tail
- Tool category tags в `tools/list` response
- Better error messages (стандартизация JSON-RPC error codes)
- Web admin UI (read-only dashboard)

### Размер jar
- 236 KB > 200 KB roadmap target. Trim в v0.4:
  - shading + trimming `com.niagaramcp.json` (~30 KB savings)
  - dedup javadoc per-tool (~10-15 KB)
  - JSON-only knowledge format (drops yaml/, ~22 KB) — если
    выяснится что operators редко правят файл вручную

### Build system
- Bajadoc encoding fix (UTF-8 для русских javadoc) — 5 LOC
- Self-contained gradle build для open source contributors —
  свой gradlew/wrapper/settings в `niagaramcp/`, чтобы не требовать
  TridiumEMEA workspace

### Real-world feedback нужен
- Walkthrough UX iteration после первого реального объекта
- Performance profile на станции с 5000+ equipment
- Resource pagination/lazy loading стратегии
- Project Haystack import/export (если на проектах есть Haystack
  tags)

---

## Ссылки на artifacts

### Документы
- `docs/concepts/01-concept.md` — semantic enrichment концепция
- `docs/concepts/02-format.md` — knowledge.yaml schema
- `docs/concepts/03-workflow.md` — walkthrough flow
- `docs/concepts/04-roadmap.md` — v0.3.0 scope (now released)
- `docs/adr/0001-streamable-http-transport.md` — v0.2.0 design
- `docs/adr/0002-semantic-layer.md` — v0.3.0 design
- `docs/recon-2026-05-09.md` — v0.2.0 baseline recon
- `docs/recon-2026-05-13-niagara-apis.md` — v0.3.0 Niagara API surfaces
- `docs/recon-2026-05-13-mcp-and-plan.md` — v0.3.0 MCP + impl plan
- `docs/v0.2.0-implementation-notes.md` — v0.2.0 deliverable notes
- `docs/v0.3.0-implementation-notes.md` — v0.3.0 deliverable notes
- `docs/agent-briefs/v0.2.0-streamable-http-prompt.md` — Claude
  Code brief
- `docs/agent-briefs/v0.3.0-implementation-prompt.md` — Claude
  Code brief

### Code
- `niagaramcp-rt/src/com/niagaramcp/server/` — module source
- `clients/python/niagaramcp_smoke.py` — regression test client
- `clients/python/README.md` — client docs

### Releases
- `v0.1.0` — pre-session baseline
- `v0.2.0` — Streamable HTTP transport
- `v0.3.0` — Semantic Enrichment Layer

---

## Контактные точки

GitHub: github.com/mefodiytr/niagaramcp
Owner: mefodiytr
Email: mefodiytr@gmail.com
License: Apache 2.0

---

*Документ накопительный. Каждая следующая major version
добавляет свой раздел сверху. Старые разделы не редактируются.*
