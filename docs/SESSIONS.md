# Session notes: v0.2.0 → v0.5.2 release work

**Версии:** v0.1.0 → v0.2.0 → v0.3.0 → v0.3.1 → v0.4.0 → v0.4.1 → v0.5 → v0.5.1 → v0.5.2
**Время:** несколько сессий, май 2026.

---

## v0.5.2 — write-tool polish (2026-05-11)

**Branch:** `v0.5.2-write-tools-polish` — off **post-merge `main`**
(v0.5 + v0.5.1 смержены в `3189c1d`; v0.5.2 — первая ветка от чистого
main, не стэк). **Commits:** 4 (a/b/d/e) + docs.
**Build:** per-commit `:compileJava` green.

### Что добавлено / изменено

- **`BValueCoercer`** (item a) — вынес дублирующуюся JSON↔BSimple
  coercion из `SetSlotTool`/`InvokeActionTool` в один package-private
  хелпер (`coerce` / `toJsonScalar` / `typeSpec` / `isSupported` +
  `UnsupportedTypeException`). Без изменения поведения. Оба тула теперь
  делегируют; их приватные копии + bespoke-исключения удалены.
- **`clearSlot`** (item d) — новый `write`-тул: ресет Property-слота в
  `prop.getDefaultValue()` под user-Context gateway. Работает для любого
  Property-типа (не только BSimple — coerce не нужен). Result:
  `{ord, slotName, previousValue, defaultValue, type, changed}`.
  `tools/list` 45 → 46.
- **`unlinkSlots` форма `{sinkOrd, linkName}`** (item e) — рядом с
  `{linkOrd}`. Валидация "linkOrd XOR (sinkOrd & linkName)" в `call()`.
  Заодно фикс латентного бага: `BLink` — это `BRelation`, не
  `BComponent`, поэтому у него нет `getSlotPath()`; `linkSlots`/
  `unlinkSlots` теперь строят link-ord из ord'а sink'а + имени слота
  (раньше падало в else-ветку `getSlotPathOrd().toString()` → относительный
  `slot:/...`, тот же класс бага, что чинил `cadf51c`).
- **`addExtension` pre-check** (item b) — `parent.isChildLegal(ext)` +
  `ext.isParentLegal(parent)` перед add; несовместимая пара → `-32015`
  ERR_EXTENSION_NOT_APPLICABLE с `data{parentOrd, parentType,
  extensionType, reason}` вместо generic `-32603`. Активирует
  declared-but-dormant `-32015`. Дефолты возвращают true → unconstrained
  parents не затронуты.
- Smoke: новый шаг 28 `clearSlot` (ресетит `note` из шага 27, проверяет
  `changed`); шаги перенумерованы 26-32; баннер обновлён.
- `getFeatureDump` error-code таблица: `-32015` теперь active.

### Что обнаружилось при разработке

- **`getSlotPathOrd()` == `BOrd.make(getSlotPath())`** (декомпиляция baja)
  — стрингуется в относительный `slot:/...`. Поэтому канонический ord для
  результатов — `"station:|" + getSlotPath()` (`Ords.stationOrd`,
  введён в v0.5.1 post-smoke).
- **`BLink extends BRelation`** (не `BComponent`). `BRelation` —
  `BComplex`, но не `BComponent` → нет `getSlotPath()`. Source/sink
  компоненты у линка — `BComponent` (`getSourceComponent()` и т.д.), а
  сам линк — нет.
- **`BComponent.isChildLegal`/`isParentLegal`** дефолт = `return true`;
  constrained-сабклассы (`BHistoryExt` и т.п.) переопределяют. Это и есть
  то, что `add()`'s `checkAdd` консультирует — годится для pre-check'а
  без recon'а agent-registry.
- **`Property.getDefaultValue()`** — есть, отдаёт declared default слота
  (для динамического свойства — значение, с которым его добавили).

### Branch state

- `v0.5.2-write-tools-polish` — unmerged, off `main` (665f06c).
- `main` — at `3189c1d` (v0.5+v0.5.1 merge) + `665f06c` (gitignore chore).
- Метки `v0.5-user-context` / `v0.5.1-write-tools` — оставлены.

---

## v0.5.1 — M1 write-tools tail (2026-05-10)

**Branch:** `v0.5.1-write-tools` — **stacked на `v0.5-user-context`**
(оба unmerged). Rebase plan на main после merge'а v0.5 — content
identical, hashes change.
**Commits:** 8 атомарных. **LOC:** +1 662 / −5 = **+1 657 net**.
**Jar:** ≈230 KiB (v0.5) → ≈238 KiB estimated.

### Что добавлено

- **6 новых write-tools** (плюс v0.5 createComponent = M1 set
  complete):
  - `removeComponent` (DESTRUCTIVE) — `parent.remove(slot, cx)`,
    default `dryRun=true`, inbound-link safety check,
    `force=true` override. Outbound (component-as-source) detection
    отложено до v0.6 (требует station walk).
  - `setSlot` (MUTATION) — `BComplex.set(prop, value, cx)` с
    BSimple coercion (BString/BBoolean/BInteger/BLong/BFloat/BDouble).
    Complex types (BStatusValue/BFacets/...) — refuse с -32602 +
    hint.
  - `invokeAction` (MUTATION) — `BComponent.invoke(Action, BValue, cx)`,
    parameter coercion из default's class. Returns `{returnValue,
    returnType, durationMs}`.
  - `addExtension` (MUTATION) — `parent.add(name, extInstance, cx)`
    для extension types. v0.5.1 без type-applicability pre-check
    (Niagara валидирует в add(), -32603); pre-check + dedicated
    -32015 — v0.5.2.
  - `linkSlots` (MUTATION) — `sink.makeLink(source, sourceSlot,
    sinkSlot, cx) + sink.add(linkName, link, cx)`. Pre-check через
    `sink.checkLink(...)` → `LinkCheck.isValid()` → -32016
    ERR_LINK_TYPE_MISMATCH с reason. Auto-pick converter
    (`convert: true`) — отложено в v0.5.2.
  - `unlinkSlots` (DESTRUCTIVE) — `sink.remove(linkProperty, cx)`.
    Refuse если ord не BLink (предотвращает случайное удаление
    обычных компонент).
  - `commitStation` (MUTATION) — `BStation.doSave(Context)` под
    user-Context. Использует Context-taking variant вместо
    no-arg `save()` чтобы permission-check работал через gateway.
- **4 новых error code** (-32013..-32016):
  - `ERR_COMPONENT_HAS_INBOUND_LINKS` (removeComponent refused)
  - `ERR_ACTION_NOT_FOUND` (invokeAction)
  - `ERR_EXTENSION_NOT_APPLICABLE` (declared, активно используется
    с v0.5.2)
  - `ERR_LINK_TYPE_MISMATCH` (linkSlots checkLink failure)
- **Smoke client +6 шагов** (26-31): createComponent fixture →
  setSlot → invokeAction error-path → commitStation →
  removeComponent dryRun preview → removeComponent actual cleanup.
  Total: 25 (v0.5) → 31 (v0.5.1). Reuses v0.5 pre-flight (test
  BUser, enableTestSetup).

### Что обнаружилось при разработке

- **`BStation` имеет и `save()`, и `doSave(Context)`.** No-arg
  variant — convenience, идёт под default cx (= service identity);
  Context-taking — propagates user identity. Используем второй для
  audit'а.
- **`BLink` хранится как child slot на sink-компоненте.**
  Поэтому `sink.getLinks()` возвращает inbound links — этого
  достаточно для removeComponent safety check без station walk.
- **`LinkCheck` API чистый**: `BComponent.checkLink(...)` отдаёт
  `LinkCheck` с `isValid()` + `getInvalidReason()`. Niagara сама
  type-compatibility predicate, мы только surface причину в
  -32016 data.
- **`BComplex.getPropertyInParent()` — каноничный slot resolver**.
  Initial RemoveComponentTool draft использовал
  `parent.getProperty(target.getSlot(target))` который не
  composes; recon поправил до commit'а. Same fix применился в
  unlinkSlots.

### Что отложено в v0.5.2 / v0.6

- Auto-pick `BTypeConverter` на link-mismatch (convert flag +
  named converterType).
- `addExtension` type-applicability pre-check + активация
  -32015.
- `BValueCoercer` helper — dedupe SetSlot ↔ InvokeAction
  primitive coercion (cosmetic).
- `unlinkSlots` ergonomic args `{sinkOrd, linkName}` (alongside
  current `{linkOrd}`).
- Smoke e2e fixtures для addExtension / linkSlots / unlinkSlots
  (требуют station-specific helper).
- `clearSlot` tool (отдельная семантика от setSlot — reset to
  type default).

### Branch state

- `v0.5.1-write-tools` — unmerged (stacked).
- `v0.5-user-context` — unmerged (under review).
- `main` — at v0.4.1 merge.
- Stack: main (v0.4.1) → v0.5-user-context (11 commits) →
  v0.5.1-write-tools (8 + 5 fix commits).

### Surprises

- M1-tail оказался mechanically rep — все tools follow
  createComponent reference shape (resolve / validate / OpDesc /
  gateway.run). Каждый tool ≈ 200-280 LOC, без новой
  архитектурной работы.
- 7-tool batch shipped в одном branch'е без блокеров — recon из
  v0.5 design pass покрыл API surface'ы заранее.

### Real-station smoke bring-up (2026-05-11)

Прогон smoke против живой 4.15.3.28 — 5 fix-коммитов поверх
`v0.5.1-write-tools`, итог **33 passed / 0 failed**.

- **`mcp:tokenHash` персистится без cx** — `BUser.tags().set(Tag)`
  без Context отрабатывает; user-Bearer auth заработала сразу после
  пересборки jar'а (первые 401 были из-за того, что smoke не
  перехватывал `Mcp-Session-Id` из ответа `initialize()` на v0.5/v0.5.1
  путях — fix `eca9523`/`4c879b7`). Диаг-поля в `setupTestUser`
  (`tagsSetReturned`/`readbackHash`/`readbackMatches`) добавлены для
  этого расследования и оставлены.
- **`slot:/...` ord на servlet-потоке резолвится от локального хоста.**
  `createComponent` возвращал `slot:/Drivers/Foo` (через
  `getSlotPath()`), и `BOrd.make("slot:/...").get()` в servlet-хендлере
  резолвил относительный путь от неявной базы потока — локального хоста
  → `ord not resolvable: localhost`. Декомпиляция baja:
  `getSlotPathOrd()` == `BOrd.make(getSlotPath())` — тоже относительный,
  так что первый fix (`4612c06`) не помог. Решение — хелпер
  `tools.Ords`: `resolve(s)` = `BOrd.make(s).get(Sys.getStation())`
  (относительный `slot:/...` от корня станции, абсолютные ords базу
  игнорируют) + `stationOrd(c)` = `"station:|" + c.getSlotPath()` для
  результатов. Прошито во все write-тулзы (`cadf51c`).
- **Коды ошибок тулзов терялись.** Tool, бросающий `RpcException`
  (-32014 и т.д.), отдавался как `{isError:true, content:[{text:"Error:
  ..."}]}` — код не доезжал до клиента (определять -32013..-32016 было
  незачем). `McpProtocol.callTool` теперь ловит `RpcException` отдельно
  и кладёт код/data в `result.errorCode`/`result.errorData` (`271e8e6`).
- **Smoke step 27 был невалиден** — `setSlot("displayName")` на голом
  `baja:Folder`, у которого нет настраиваемого скалярного слота (тул
  правильно отвечал `No such slot`). Теперь: `createComponent`
  динамического `baja:String`-свойства `note` на фикстуре (тул
  принимает любой `BValue`, не только компоненты), потом `setSlot`.
- **`enableTestSetup=true` на станции** — это флаг для smoke/CI; снять
  обратно в `false` после прогонов.

---

## v0.5 — User-Context gateway + per-user audit (2026-05-10)

**Branch:** `v0.5-user-context` (off `main` после v0.4.1 merge).
**Commits:** 10 атомарных. **LOC:** +1 867 / −19 = **+1 848 net**.
**Jar:** 220.4 KiB → ≈230–235 KiB (estimated, +20 class files; точное
число — после следующего clean assemble).

### Что добавлено

- **User-Context auth pipeline**: Bearer → `BUser` через walk
  `BUserService.getUsers()` + constant-time compare против
  `mcp:tokenHash` тэга. Legacy `apiToken` остаётся read-only
  service identity для мониторинга / read-tools; write-tools под
  `requiresUserContext=true` отбивают apiToken через
  `-32011 ERR_USER_NOT_FOUND` ДО dispatch'а.
- **`UserContextGateway.run(BUser, OpDesc, args, sessionId,
  ContextAwareWork<T>)`** — единственная entry-point для write-tools.
  Строит `BasicContext(user)`, оборачивает `PermissionException` в
  `-32010` с rich `data{user, ord, operation, tool, detail}`,
  emit'ит один audit-record на вызов.
- **Audit pipeline**: JSONL primary (всегда on, full record:
  `{ts, user, sessionId, tool, ord, action, args, resultOk,
  durationMs, errorCode, errorMessage}`), best-effort secondary
  через `BAuditHistoryServiceAdapter` — reflection-only на
  `com.tridium.history.audit.BAuditHistoryService`, никакого
  compile-time dep на `history-rt` (lightweight JACE стартует без
  `NoClassDefFoundError`). Redactor с regex blacklist
  `(password|secret|token|apikey|passcode|pwd|credential)` +
  256-char string truncation.
- **Tool interface**: 2 default-метода — `requiresUserContext()`
  (default `false`) и `annotations()` (default
  `ToolAnnotations.READ_ONLY`). Per MCP 2025-06-18 §6.1, каждая
  строка `tools/list` теперь несёт `annotations:{readOnlyHint,
  destructiveHint, idempotentHint, openWorldHint}` плюс
  niagaramcp-extension `requiresUserContext`. Существующие 36 tools
  наследуют дефолты — zero touch.
- **Auto-promote** JSON-string tool result в
  `result.structuredContent` (per spec §5.4) — purely additive,
  legacy clients продолжают читать `content[0].text`. Бэкфилл,
  обещанный в Q11, случился автоматически для всего каталога.
- **`createComponent`** (category `write`, annotations `MUTATION`,
  requiresUserContext) — reference write-tool: добавляет fresh
  `BComponent` указанного типа как child существующего parent'а
  под calling user permissions. Args:
  `{parentOrd, type, name, nameStrategy?:"fail"|"suffix"}`.
- **`setupTestUser`** (test-only, gated by
  `enableTestSetup` property; default false) — для smoke step 25:
  биндит `mcp:tokenHash` тэг к pre-created BUser'у через apiToken,
  чтобы smoke мог подключиться под user-Bearer без bog-fragment'а в
  pre-deployment runbook.
- **Smoke step 25** — e2e mutation: smoke генерит фреш-токен,
  setupTestUser биндит, reconnect под user-Bearer, createComponent,
  проверяет `structuredContent.ord`. Покрывает реальный pipeline
  (BearerResolver → CallContext → Gateway → BasicContext(user) →
  permission-checked `parent.add` → audit).
- **2 новых JSON-RPC error code**: `-32010 ERR_PERMISSION_DENIED`,
  `-32011 ERR_USER_NOT_FOUND`.
- **2 новых property**: `tokenSalt` (READONLY, lazy-generated),
  `enableTestSetup` (bool, default false).

### Что обнаружилось при разработке

- **`Sys.makeContext` / `BUserService.runAs` / `BLocalSession.makeContext`
  не существуют.** В Niagara нет thread-local context; `Context`
  передаётся explicit в каждый mutating-вызов. Gateway-сигнатура
  переразвернулась с `run(BUser, ThrowingSupplier<T>)` на
  `run(BUser, OpDesc, args, sessionId, ContextAwareWork<T>)` — work
  получает cx и threads его в каждый `parent.add(name, val, cx)`.
- **`BUser implements Context, Principal`** — BUser сам по себе
  Context. `new BasicContext(user)` нужен только для default
  facets, можно и без обёртки.
- **`BAuditService` отсутствует в публичном API.** Есть только
  interfaces `Auditor.audit(AuditEvent)` + `SecurityAuditor`.
  Concrete service `com.tridium.history.audit.BAuditHistoryService`
  — non-public, не на каждой станции. Перевернуло Q5 design:
  JSONL primary, BAuditHistoryService через reflection-adapter.
- **`PermissionException` без rich payload** — только String message.
  Wrapper собирает `data{user, ord, operation, tool}` из call-site
  `OpDesc` (поэтому OpDesc — отдельный параметр, не derivable из
  exception'а).
- **TagDictionary auto-bootstrap отскоплен**. `BTagDictionary`
  programmatic-construction требует populated `BTagInfoList` — это
  отдельный ~100-LOC feature. Commit 4 ship'нул schema-constants +
  reflection-check «registered already?»; programmatic register —
  v0.5.x. Tag write/read работает без registration; Workbench
  TagBrowser entry — опционально.

### Что отложено в v0.5.x / v0.6

- Programmatic BTagDictionary auto-construction.
- JSONL audit log size-rotation.
- Operator-configurable `auditRedactPattern` property.
- Workbench action `generateUserToken(BString)` + MCP tool
  `rotateMcpToken`.
- `writePoint` retrofit к `requiresUserContext=true`.
- M1 write-tools хвост (по шаблону `createComponent`):
  `removeComponent`, `setSlot`, `invokeAction`, `addExtension`,
  `linkSlots`/`unlinkSlots`, `commitStation` — единый batch.

### Branch state

- `v0.5-user-context` — unmerged (10 commits).
- `main` — at v0.4.1 merge.
- Stack: main (v0.4.1 merged) → v0.5-user-context (10 commits,
  unmerged).

### Surprises

- Plan был на 9 commits; +1 на ходу — `SetupTestUserTool`
  extracted из commit 9 в commit 10, чтобы production tool
  (createComponent) не смешивался в одном review-юните с
  test-only setupTestUser + enableTestSetup property.
- Auto-promote JSON-string результата в `structuredContent` —
  один-к-одному закрыло Q11 backfill «бесплатно» для всех 36
  существующих tools.
- Brief estimate "~100 LOC for gateway" не учитывал supporting
  packages (auth + audit + Tool interface + smoke). Финал ~1 800
  LOC. Каждая часть осталась "small + focused"; собранный объём —
  цена за full pipeline в одном release'е.

---

## v0.4.1 — UX polish before real-world tests (2026-05-10)

**Branch:** `v0.4.1-ux-polish` (off `main` после v0.4 merge).
**Commits:** 5 атомарных. **LOC:** +702 / −9 = **+693 net**.
**Jar:** 216.6 KB → **220.4 KiB** (+4 %, target ≤220 KB на грани, hard
ceiling ≤225 KB ✓).

### Что добавлено

- **4 read-only count properties** на `BMcpPlatformService`
  (flags=3 → SUMMARY+READONLY): `toolCount` (36), `resourceCount`
  (6), `promptCount` (7), `sessionCount` (live, refresh
  через notification из McpSessions create/remove/closeAll — без
  polling, без новых потоков). Видны на Workbench Property Sheet —
  оператор видит runtime-стат при открытии сервиса.
- **36-й tool `getFeatureDump`** (category `diagnostic`) — статический
  feature-инвентарь сервера, два формата: `text` (банер для
  операторов) / `json` (для AI-клиентов). Включает версию, tools по
  категориям, resources split (static / templates), prompts,
  transport-флаги, knowledge-стат, sessions, health и 9
  impl-defined JSON-RPC error-кодов с расшифровкой.
- Smoke client +2 шага (steps 23–24, getFeatureDump text + json),
  всего **24** (брифу казалось 24 → 26, но baseline был 22).
- `NIAGARAMCP_VERSION` bump 0.4.0 → 0.4.1.

### Что не добавлено (исследование задокументировано)

- **`BFilePath` для `knowledgeFilePath`** — `BFilePath` не существует
  в baja 4.15.3.28; `FilePath` есть (но не BSimple); `BAbstractFile`
  только для in-station file space; `BFacets` не имеет
  `FILE_BROWSE` key. Workbench file-picker UX отложен до появления
  `niagaramcp-wb` subproject. 19 LOC javadoc на property
  объясняет почему.

### Что отложено в v0.5+

- Per-transport `sessionCount` split (SSE vs Streamable) — нужно
  расширение McpSessions API.
- `niagaramcp-wb` subproject: file-picker для `knowledgeFilePath`,
  custom Property Sheet renderer для `sessionCount` (live counter),
  PX-виджеты.

### Branch state

- `v0.4.1-ux-polish` — unmerged.
- `main` — at v0.4 merge.
- Stack теперь: main (v0.4 merged) → v0.4.1-ux-polish (5 commits,
  unmerged).

### Surprises

- Выбрал **notification-pattern** для `sessionCount` вместо
  `BService.tick()` (бриф рекомендовал tick). Notifications дают
  exactness (без 1-сек лага), не требуют тик-handler-регистрации,
  и общий принцип "no new threads" сохраняется.
- Brief assumed 24 baseline smoke steps — реально было 22. Новый
  total = 24, не 26. Зафиксировано в notes.
- Auto-install copy task снова падает на station-locked jar (как в
  v0.3.0/v0.3.1/v0.4) — разовая ручная замена при остановленной
  станции.

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

## v0.3.1 — Diagnostic capabilities + samples (2026-05-10)

> Backfilled in v0.4.1 commit 5: эта секция была пропущена при
> добавлении v0.4-секции в commit 8 предыдущей сессии.

**Branch:** `v0.3.1-diagnostics` (off `main` at tag `v0.3.0`).
**Commits:** 7 атомарных. **LOC:** +1 512 / −20 = **+1 492 net**.
**Jar:** 236.4 KB → **243.4 KB** (+7 KB / +3 %).

### Что добавлено

- **4 диагностических tools** (28 → 32):
  - `getServerInfo` — snapshot version/uptime/sessions/knowledge
    file/transports/registered tools/resources/prompts.
  - `probeOrd {ord}` — resolve ord, отдаёт exists/type/displayName/
    parentOrd/slotCount + isControlPoint/isWritable/isAlarmSource +
    hasHistoryExt + historyExtCount/historyExtIds. Garbage ords
    дают `{exists:false}`, не error.
  - `checkKnowledgeIntegrity` — пройти каждый ord в knowledge model,
    репортить broken refs.
  - `getServiceHealth` — alarm/history availability + knowledge file
    readability/writability + sample-resource read-back.
- **Unauthenticated `/health` endpoint** — единственное исключение
  из Bearer-on-everything. Для k8s/Prometheus/watchdog. Отдаёт
  `200 + {status:"ok", version, uptimeSeconds, knowledgeFileSize,
  sessionCount, healthyServices}` в норме / `503 + status:"degraded"`
  при проблемах. Не показывает station data, equipment, ords.
- **3 новых operator-property**:
  - `mcpProtocolVersion` (String) — override MCP protocol version в
    initialize (default empty → "2025-06-18").
  - `maxHistoryRecordsPerQuery` (int, default 10000) — cap для
    `readHistory`.
  - `disabledTools` (String, comma-separated) — оператор может
    подавить tool'ы при старте. Restart-required.
- **8 standardised JSON-RPC error codes** (-32001..-32008): session/
  tool/resource/knowledge/schema/ord/history/alarm. Error responses
  с `error.data` JSONObject (`{toolName: ...}`, `{uri: ...}` и т.д.).
- **`samples/` папка** (вне jar): `mall-knowledge.yaml` (синтетический
  shopping mall fixture, ~14 equipment / 12 spaces) +
  `samples/README.md`. Операторы импортят через `importKnowledge`
  чтобы тестить queries без реального walkthrough.
- Smoke client +6 шагов (14–19), всего 19.

### Что обнаружилось при разработке

- `BHistoryService.getHistoryDb()` не существует — правильно
  `getDatabase()`.
- `HistorySpaceConnection.timeQuery` возвращает `BITable`, не
  `HistoryCursor`.
- `BAlarmRecord.getSource()` возвращает `BOrdList` напрямую, не
  `BAlarmSource`.

Все 3 находки исправлены в тех же commit'ах + задокументированы.

### Что отложено в v0.4

- Tool category tags в tools/list.
- Combined diagnostic snapshot (получит имя `getDiagnosticDump` в
  v0.4).
- Transport toggles (sseEnabled/streamableEnabled).

### Branch state

- `v0.3.1-diagnostics` — unmerged (left for human review per брифу).
- `main` — at v0.3.0 merge + recon.
- v0.3.0 tag exists, v0.3.1 tag не создан.

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
