# niagaramcp — MCP-сервер для Tridium Niagara

> **MCP (Model Context Protocol) server for Tridium Niagara stations.**
> Niagara-модуль (`BIService`), отдающий JSON-RPC через HTTP/SSE — позволяет MCP-совместимым AI-ассистентам (Claude, ChatGPT, IDE) читать/писать точки, обходить slot-tree и исполнять BQL-запросы на живой станции под bearer-token аутентификацией.

🌐 [English](./README.md) · [Русский](./README.ru.md)

---

## Что это и зачем

**Model Context Protocol (MCP)** — открытый протокол от Anthropic для подключения LLM-ассистентов к внешним системам. AI-клиент договаривается с сервером о наборе доступных «инструментов» (tools), вызывает их и получает структурированные ответы.

**niagaramcp** делает Niagara-станцию таким MCP-сервером. После установки модуля и запуска `McpPlatformService` ассистент получает реальный доступ к данным станции:

- читать значения и метаданные точек (`readPoint`),
- писать в writable-точки с приоритетом 1..16 (`writePoint`),
- обходить slot-tree без BQL (`listChildren`),
- исполнять BQL-запросы и получать табличные результаты (`bqlQuery`),
- проверять связь (`echo`).

Кейсы использования:
- инженер-интегратор отлаживает логику — спрашивает у ассистента «какие точки в Drivers/Modbus сейчас в фолте» и получает живой ответ;
- ассистент составляет отчёт по графику и BQL-выборке без ручного экспорта;
- self-service tooling: пользователи запрашивают изменение setpoint через чат, ассистент валидирует и применяет.

Основное преимущество — **никаких MCS / cloud / отдельных gateway**. Станция сама — MCP-сервер.

⚠ **Безопасность не для прода в текущем виде.** См. ниже раздел «Безопасность».

---

## Системные требования

- **Tridium Niagara 4.15.3.x** (тестировалось на 4.15.3.28).
- Установленные стандартные модули `baja-rt`, `control-rt`, `web-rt` (есть в любой стандартной поставке Niagara).
- Java 8 для сборки. Tridium Gradle plugins из дистрибутива Niagara (`$niagara_home/etc/m2/repository`).
- Подписанный модуль. Сборочный скрипт ожидает либо dev-сертификат Niagara (`Niagara4Modules`), либо ваш enterprise-cert.

Совместимость с Niagara 4.13/4.14 и 4.16+ не проверялась. Возможно, потребуется лишь поднять `vendorVersion` в зависимостях.

---

## Установка

### 1. Сборка из исходников

```powershell
cd C:\path\to\niagaramcp
.\..\..\gradlew.bat :niagaramcp-rt:jar --configure-on-demand
```

Готовый jar появится в `niagaramcp-rt\build\libs\niagaramcp-rt.jar`.

> Флаг `--configure-on-demand` нужен только если в родительском `TridiumEMEA/` есть посторонние сломанные gradle-проекты (например, в `_research/`). Для чистого checkout'а можно убрать.

### 2. Установка в Niagara

```powershell
Copy-Item niagaramcp-rt\build\libs\niagaramcp-rt.jar `
          C:\Niagara\Niagara-4.15.3.28\modules\ -Force
```

Перезапустите станцию.

### 3. Конфигурация

В Workbench:

1. Откройте палитру `niagaramcp`.
2. Перетащите **`McpPlatformService`** в `Services/` нужной станции.
3. На сервисе задайте:
   - `enabled` = `true`
   - `apiToken` = криптостойкий случайный UUID (минимум 128 бит энтропии). **Это эквивалент admin-пароля станции.**
   - `sseHeartbeatSec` = 25 (default, можно поднять для дальних/медленных сетей).
   - `showLog` = `true` на время отладки.
4. Сохранить, station-restart не требуется (`changed()` подхватит).

После этого endpoint доступен по `https://<station-host>/niagaramcp/`.

---

## API endpoints

Все endpoints требуют заголовок `Authorization: Bearer <apiToken>`.

| Method | Path | Назначение |
|---|---|---|
| `GET` | `/niagaramcp/sse` | Server-Sent Events stream. Первое событие — `endpoint` с URL для POST-запросов. Далее — heartbeat'ы (`: ping`) и `event: message` с JSON-RPC ответами. |
| `POST` | `/niagaramcp/messages?sessionId=<uuid>` | JSON-RPC 2.0 запрос. Принимается, ответ enqueue'ится в SSE-поток. Возвращает `202 Accepted`. |

JSON-RPC методы:
- `initialize` → возвращает `protocolVersion`, `capabilities`, `serverInfo`.
- `notifications/initialized` → клиент сообщает что готов.
- `ping` → `{}`.
- `tools/list` → список из 5 tools с inputSchema.
- `tools/call` → `{name: <toolName>, arguments: {...}}` → `{content: [{type:"text", text:"..."}], isError: <bool>}`.

### Tools

| name | назначение | params |
|---|---|---|
| `echo` | вернуть переданное `msg` | `msg: string` |
| `listChildren` | дерево потомков узла | `ord: string`, `depth: 1..5` |
| `readPoint` | значение и метаданные `BControlPoint` | `ord: string` |
| `writePoint` | запись в writable-точку | `ord: string`, `value: any`, `priority: 1..16` (default 16) |
| `bqlQuery` | BQL → TSV (timeout 10 сек) | `query: string` (полный ord с `bql:`), `limit: 1..1000` (default 100) |

### Пример вызова

```bash
TOKEN=<your-uuid>

# 1. Открываем SSE и читаем endpoint
curl -N -H "Authorization: Bearer $TOKEN" \
     https://station/niagaramcp/sse
#  event: endpoint
#  data: /niagaramcp/messages?sessionId=abc-123

# 2. В другом окне — initialize
curl -X POST -H "Authorization: Bearer $TOKEN" \
     "https://station/niagaramcp/messages?sessionId=abc-123" \
     -d '{"jsonrpc":"2.0","id":1,"method":"initialize"}'

# 3. tools/list
curl -X POST -H "Authorization: Bearer $TOKEN" \
     "https://station/niagaramcp/messages?sessionId=abc-123" \
     -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'
```

Подключение из MCP-клиента (Claude Desktop, Continue.dev, и т.п.) — стандартный SSE-transport, URL `/niagaramcp/sse`, header `Authorization: Bearer <token>`.

---

## Streamable HTTP transport (v0.2.0)

С версии v0.2.0 модуль также говорит **Streamable HTTP** (MCP-спека
2025-06-18) на том же сервлете, рядом с легаси SSE+messages. Новые
клиенты, дефолтно использующие Streamable HTTP, подключаются без
конфигурационных изменений на сервере.

| Method | Path | Назначение |
|---|---|---|
| `POST`   | `/niagaramcp/mcp` | JSON-RPC inbound. Первый запрос обязан быть `initialize`; сервер возвращает свежий `Mcp-Session-Id` в response-header. Последующие запросы шлют этот id обратно. |
| `GET`    | `/niagaramcp/mcp` | Server-to-client SSE-канал. Сейчас вырожденный (немедленное закрытие) — текущие tools не генерируют push-сообщений. |
| `DELETE` | `/niagaramcp/mcp` | Явное закрытие сессии. Идемпотентно. |

Модель сессии:

- Заголовок **`Mcp-Session-Id`** (request/response) несёт UUID.
- Сессии вытесняются лениво после `mcpSessionIdleTimeoutSec` бездействия
  (по умолчанию **30 минут**, настраивается на сервисе).
- `DELETE /mcp` удаляет сессию сразу.

### Curl-пример

```bash
TOKEN=<your-uuid>

# 1. initialize — сервер вернёт Mcp-Session-Id в заголовках
SID=$(curl -sS -D - -H "Authorization: Bearer $TOKEN" \
       -H "Content-Type: application/json" \
       https://station/niagaramcp/mcp \
       -d '{"jsonrpc":"2.0","id":1,"method":"initialize"}' \
     | tr -d '\r' | awk -F': ' '/^Mcp-Session-Id/{print $2}')
echo "session: $SID"

# 2. tools/list
curl -sS -H "Authorization: Bearer $TOKEN" \
     -H "Content-Type: application/json" \
     -H "Mcp-Session-Id: $SID" \
     https://station/niagaramcp/mcp \
     -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'

# 3. tools/call echo
curl -sS -H "Authorization: Bearer $TOKEN" \
     -H "Content-Type: application/json" \
     -H "Mcp-Session-Id: $SID" \
     https://station/niagaramcp/mcp \
     -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"echo","arguments":{"msg":"hi"}}}'

# 4. close
curl -sS -X DELETE -H "Authorization: Bearer $TOKEN" \
     -H "Mcp-Session-Id: $SID" \
     https://station/niagaramcp/mcp
```

`/sse` + `/messages` транспорт остаётся полностью поддержан;
существующие v0.1.0-клиенты работают без изменений.

---

## Semantic layer (v0.3.0)

С версии v0.3.0 модуль ведёт **knowledge-файл** в
`${niagaraUserHome}/niagaramcp/knowledge.yaml` — семантическая
модель станции (spaces, equipment_types, equipment, standalone
points). AI-клиенты используют его, чтобы отвечать на естественные
вопросы про станцию без brute-force обхода slot-tree.

### Как это работает

1. Оператор + AI запускают **walkthrough** — AI смотрит структуру
   станции, задаёт уточняющие вопросы, пишет подтверждённые
   соответствия в knowledge.yaml.
2. После walkthrough'а AI грузит spine-resources
   (`niagara://overview`, `niagara://kinds/catalog`) при старте и
   имеет полную карту в контексте.
3. Запросы вроде *«какая supply temp на паркинге сектор E»*
   решаются за один round-trip через `findEquipment` + `readPoint`.

См. `docs/concepts/01-concept.md` … `04-roadmap.md` для деталей.

### Tools (23 новых, всего 28)

| Категория | Tools |
|---|---|
| Walkthrough read | `getOverview`, `inspectComponent`, `findComponentsByType`, `getSlots` |
| Walkthrough write | `createSpace`, `updateSpace`, `createEquipmentType`, `updateEquipmentType`, `createEquipment`, `updateEquipment`, `bulkCreateEquipment`, `assignPointToEquipment`, `createStandalonePoint`, `validateKnowledge` |
| Knowledge mgmt | `getKnowledgeSummary`, `findUnmappedComponents`, `exportKnowledge`, `importKnowledge`, `reloadKnowledge` |
| Search | `findEquipment`, `findInSpace`, `findPoints` |
| History | `readHistory` (с опциональной client-side агрегацией) |
| Alarms | `getActiveAlarms`, `getAlarmHistory` |

### Resources

| URI | Описание |
|---|---|
| `niagara://overview` | static; идентификация станции + счётчики |
| `niagara://kinds/catalog` | static; полный equipment_types |
| `niagara://equipment/{id}` | template; запись equipment |
| `niagara://spaces/{id}` | template; space + содержимое |
| `niagara://standalone-points/{id}` | template; одиночный sensor |
| `niagara://samples/standard-types` | static; в jar 5 базовых типов (opt-in через `importKnowledge` source='sample') |

### Prompts

`walkthrough.new_station`, `walkthrough.continue`,
`walkthrough.verify_types`, `walkthrough.apply_pattern`,
`query.equipment_state`, `query.zone_comfort`,
`query.alarm_summary`.

См. `_SMOKE_TEST.md` секцию v0.3.0 для curl-проверки.

---

## Diagnostics & samples (v0.3.1)

v0.3.1 — patch-релиз, добавляющий диагностику и sample-данные.

### Diagnostic tools (4 новых, всего 32)

| Tool | Назначение |
|---|---|
| `getServerInfo` | Snapshot: version, uptime, sessions, knowledge-файл, transports, registered tools/resources/prompts |
| `probeOrd {ord}` | Резолв ord — exists/type/displayName/parentOrd/slotCount + флаги isControlPoint/isWritable/isAlarmSource + hasHistoryExt/historyExtCount/historyExtIds. Garbage-ord возвращает `{exists:false}`, не ошибку. |
| `checkKnowledgeIntegrity` | Пройти каждый ord в knowledge-модели, выдать broken refs |
| `getServiceHealth` | Доступность Niagara-сервисов + читаемость/писаемость knowledge-файла + sample-resource |

### Unauthenticated `/health` endpoint

```
GET /niagaramcp/health
```

ЕДИНСТВЕННОЕ исключение из Bearer-on-everything — для внешнего
мониторинга (k8s/Prometheus/watchdog). Возвращает:

- `200 OK` + JSON `{status:"ok", version, uptimeSeconds, knowledgeFileSize, sessionCount, healthyServices}` в норме.
- `503 Service Unavailable` + та же форма с `status:"degraded"` если alarm/history недоступны, knowledge нечитаем, или сервис disabled.

Содержит только счётчики и per-service ok/missing — никаких данных
станции, имён оборудования, ords.

### Новые свойства

- `mcpProtocolVersion` (String) — переопределение MCP-protocol версии в `initialize`. Default пусто → `2025-06-18`.
- `maxHistoryRecordsPerQuery` (int) — cap для `readHistory`. Default 10000.
- `disabledTools` (String, comma-separated) — оператор может отключить отдельные tools при старте. Restart-required.

### Standardised JSON-RPC error codes

8 niagaramcp-defined кодов в impl-defined band: `-32001..-32008` для session/tool/resource/knowledge/schema/ord/history/alarm. В `error.data` теперь блок с диагностикой (`{toolName: "..."}` и подобное).

### samples/ folder

`samples/mall-knowledge.yaml` (синтетический ТЦ-fixture) +
`samples/README.md`. НЕ упаковывается в jar — операторы импортят
через `importKnowledge` чтобы попробовать queries без реального
walkthrough. Встроенный `niagara://samples/standard-types` (5
generic типов) из v0.3.0 — отдельная история.

---

## Operational features (v0.4.0)

v0.4.0 — minor-релиз с операционными фичами и уменьшением jar
(243 KB → 213 KB).

### Transport toggles

2 новых property на `BMcpPlatformService`:

- `sseEnabled` (default `true`) — управляет `/sse` + `/messages`.
- `streamableEnabled` (default `true`) — управляет
  `POST/GET/DELETE /mcp`.

Отключённый transport отвечает HTTP 503 + JSON-RPC body
`{error:{code:-32009, message:"Transport disabled: …", data:{transport:…}}}`.
`/health` всегда доступен. Restart-required (v0.5 может добавить
runtime apply).

### Tool category tags

`tools/list` теперь возвращает поле `category` для каждого tool —
клиенты могут группировать в UI по категориям (`read`, `write`,
`walkthrough-write`, `diagnostic`, и т.д., всего 10 категорий).

### `getDiagnosticDump` tool (35-й)

One-shot snapshot для ops-дашбордов — собирает server identity,
sessions, knowledge stats, service health и хвост audit log в один
JSON-ответ.

### `serverInfo.transports`

`initialize`-ответ теперь имеет `serverInfo.transports` со списком
включённых транспортов. Информационно — клиенты выбирают по URL.

### Уменьшение jar

12 неиспользуемых JSON utility-классов (`XML`, `CDL`, `Cookie`,
`HTTP`, и т.д.) удалены из embedded `com.niagaramcp.json`. Jar:
243 KB → 213 KB (−30 KB / −12 %).

---

## Write-tool polish (v0.5.2)

Небольшие follow-up'ы к M1-набору — один новый тул, новая форма
аргументов, pre-check и рефакторинг. Без новой инфраструктуры.

- **`clearSlot`** (`write`, `requiresUserContext`, `MUTATION`) — ресет
  Property-слота в declared default (`Property.getDefaultValue()`) под
  user-Context gateway. Работает для любого Property-типа, не только
  BSimple (нечего coerce'ить). Args `{ord, slotName}`; result
  `{ord, slotName, previousValue, defaultValue, type, changed}`.
- **`unlinkSlots` теперь принимает и `{sinkOrd, linkName}`** рядом с
  `{linkOrd}` — отражает то, как `linkSlots` возвращает результат и как
  линк читается в nav-дереве (именованный слот на sink'е).
- **`addExtension` pre-check применимости** — гоняет родные предикаты
  Niagara `isChildLegal` / `isParentLegal`; несовместимая пара
  parent/extension теперь отбивается сразу с **`-32015
  ERR_EXTENSION_NOT_APPLICABLE`** (`data{parentOrd, parentType,
  extensionType, reason}`) вместо generic `-32603` на `add()`. Активирует
  `-32015`, объявленный в v0.5.1.
- **`BValueCoercer`** — JSON↔BSimple coercion, общая для `setSlot` и
  `invokeAction`, вынесена в один хелпер (без изменения поведения).
  Заодно фикс относительных `slot:/...` link-ord'ов в
  `linkSlots`/`unlinkSlots` (`BLink` — это `BRelation`, не `BComponent`).

`tools/list` 45 → **46**. Smoke `+1` шаг (28, `clearSlot`); шаги 26-32.

---

## M1 write-tool tail (v0.5.1)

Stacked-on-v0.5 patch. Закрывает M1 write-tool set: 6 новых tools
+ commitStation, все под v0.5 user-Context gateway, все
audit'ируются. Никакой новой инфраструктуры — чистые tool
additions по `createComponent` reference shape.

### 7 новых tools (все `category: "write"`, `requiresUserContext: true`)

| Tool | annotations | Назначение |
|---|---|---|
| `removeComponent` | `DESTRUCTIVE` | `parent.remove(slot, cx)`. Default `dryRun=true` + inbound-link safety check. Outbound (компонент-как-source) detection отложен в v0.6 (требует station walk). |
| `setSlot` | `MUTATION` | Type-coerced `BComplex.set(prop, value, cx)` для BSimple slot types. Complex types (BStatusValue/BFacets/...) — refuse с -32602 + hint. |
| `invokeAction` | `MUTATION` | `BComponent.invoke(Action, BValue, cx)` с parameter coercion как в setSlot. Returns `{returnValue, returnType, durationMs}`. |
| `addExtension` | `MUTATION` | `parent.add(name, ext, cx)` для extension types. v0.5.1 без pre-check applicability; Niagara валидирует в add(), -32603 → -32015 после v0.5.2. |
| `linkSlots` | `MUTATION` | `sink.makeLink(...) + sink.add(linkName, link, cx)` после Niagara `checkLink()`. Type mismatch → -32016 с reason. Auto-pick converter — v0.5.2. |
| `unlinkSlots` | `DESTRUCTIVE` | `sink.remove(linkProperty, cx)`. Refuses non-link ords. Захватывает wire info (source/sink + slots) в result для audit / manual undo. |
| `commitStation` | `MUTATION` | `BStation.doSave(Context)` под user-Context. Use после batch когда ack-without-persistence (~30s auto-save delay) неприемлем. |

### 4 новых error code

| Код | Symbol | Когда |
|---|---|---|
| `-32013` | `ERR_COMPONENT_HAS_INBOUND_LINKS` | `removeComponent` refused; `data{ord, inboundLinkCount, sampleSourceOrds[≤5]}`. |
| `-32014` | `ERR_ACTION_NOT_FOUND` | `invokeAction` action не найден. |
| `-32015` | `ERR_EXTENSION_NOT_APPLICABLE` | Зарезервирован; активируется когда v0.5.2 добавит pre-check. |
| `-32016` | `ERR_LINK_TYPE_MISMATCH` | `linkSlots` Niagara `LinkCheck` invalid; `data{sourceOrd, sourceSlot, sinkOrd, sinkSlot, reason}`. |

### Smoke

`+6` шагов (26-31) под существующим v0.5 pre-flight: throwaway
fixture через createComponent → добавить `baja:String`-проп +
setSlot его → invokeAction error path (проверяет
`result.errorCode == -32014`) → commitStation → removeComponent
dryRun preview → removeComponent actual cleanup. `--skip-v051`
opts out. addExtension / linkSlots / unlinkSlots e2e fixtures
отложены в v0.5.2 (нужен station-specific helper).
**33 / 33 green против живой станции 4.15.3.28.**

### Post-smoke hardening

- **ord-аргументы** — write-тулзы резолвят ords от корня станции
  (`BOrd.make(s).get(Sys.getStation())`): относительный
  `slot:/Drivers/Foo` работает наравне с полным
  `station:|slot:/Drivers/Foo`. Ords в результатах всегда полные.
- **`result.errorCode` / `result.errorData`** — `RpcException` тула
  (`-32013`..`-32016`, `-32006`, ...) по-прежнему отдаётся MCP-стайл
  через `isError`-контент, но код/data теперь лежат на `CallToolResult`
  — клиент ветвится по ним без парсинга текста. Generic-исключения —
  текст `Error: <msg>` без кода.

---

## User-Context gateway и per-user audit (v0.5)

Foundation-релиз для write-tools, мутирующих station-component-tree
под Niagara-permissions вызывающего пользователя, а не под service
identity. Без breaking changes — все 36 существующих tools работают
ровно как и раньше; новый pipeline активен только для tool'ов с
`requiresUserContext=true` (в v0.5 — только `createComponent`,
остальной M1 — v0.5.x).

### Как работает user identity

- Оператор pre-creates `BUser` через Workbench (UserService) и
  выдаёт ему Niagara-permissions, нужные tool'у.
- MCP-токен пользователя биндится к BUser через тэг
  `mcp:tokenHash` (salted SHA-256 против per-service `tokenSalt`,
  лениво генерится при первом запуске).
- На каждый запрос: `McpServlet` аутентифицирует Bearer ЛИБО
  против legacy `apiToken` (read-only service identity для
  monitoring), ЛИБО через `BearerResolver`, walking
  `BUserService.getUsers()` с constant-time hash-compare.
- Walk идёт по всем юзерам unconditionally — без early-exit на
  match — чтобы общее время не лекало enrollment list.
- Резолвнутый BUser течёт через `CallContext` в тело tool'а,
  который кладёт его в `UserContextGateway.run(...)`. Gateway
  строит `BasicContext(user)`, исполняет work-лямбду, заворачивает
  `PermissionException` в `-32010` с rich
  `data{user, ord, operation, tool, detail}`, emit'ит один
  audit-record на вызов.

### Per-user audit

- **JSONL primary**: каждый gateway-вызов пишет одну JSON-строку в
  `<userHome>/niagaramcp/niagaramcp.audit.log`:
  `{ts, user, sessionId, tool, ord, action, args, resultOk,
  durationMs, errorCode, errorMessage}`. Args проходят через
  redactor (default key blacklist:
  `password|secret|token|apikey|passcode|pwd|credential`),
  длинные string-значения truncate'ятся до 256 chars с `…+N`
  суффиксом.
- **BAuditHistoryService secondary**: best-effort,
  **reflection-only**. Lookup на service-старте; method handle
  cached. Никакого compile-time dep на `history-rt` —
  lightweight JACE без этого модуля стартует чисто. Когда сервис
  есть, наши записи видны в Workbench AuditView с 6-field
  маппингом (operation = action, target = ord, slotName = tool,
  value = "ok"|"FAIL: …", userName = user).

### MCP tool annotations

Per MCP 2025-06-18 §6.1, каждая строка `tools/list` теперь несёт
`annotations: {readOnlyHint, destructiveHint, idempotentHint,
openWorldHint}` плюс niagaramcp-extension `requiresUserContext`.
MCP-aware клиенты (Claude Desktop, Cursor, MCP Inspector) гейтят
user-visible warnings на этих полях — например запрашивают
explicit confirmation перед `destructiveHint=true`. Существующие
36 tools наследуют `READ_ONLY`-дефолты — zero touch.

### `createComponent` tool (37-й)

Reference write-tool для M1-набора. Добавляет новый `BComponent`
указанного типа как child существующего parent'а под calling
user permissions.

```
{
  "parentOrd":    "station:|slot:/Drivers",
  "type":         "baja:Folder",
  "name":         "BasementHVAC",
  "nameStrategy": "fail" | "suffix"     // опционально, default "fail"
}
```

Возвращает (в `content[0].text` И в `structuredContent`):
`{ord, displayName, requestedName, resolvedName}`.

Errors: -32602 (missing arg / collision под "fail"), -32006
(parentOrd не резолвится), -32005 (type не грузится), -32010
(permission denied), -32011 (bearer = service identity, не BUser).

### Auto-promote `structuredContent`

McpProtocol теперь auto-promote'ит JSON-shape результаты tool'ов
в MCP `result.structuredContent` per spec §5.4. Чисто добавление:
legacy-клиенты продолжают читать `content[0].text`, modern —
typed structuredContent. Затрагивает весь каталог из 36 tools без
per-tool изменений.

### `setupTestUser` tool (38-й, test-only)

Gated через `BMcpPlatformService.enableTestSetup` (default
`false`). Когда включён — позволяет smoke-клиенту биндить
свежесгенерённый Bearer к pre-created BUser'у (`mcp:tokenHash`
тэг), чтобы v0.5 e2e smoke-step (createComponent под
user-Bearer) работал без bog-fragment'а в pre-deployment.
Production-деплой держит флаг выключенным.

### 2 новых error code

| Код | Symbol | Когда срабатывает |
|---|---|---|
| `-32010` | `ERR_PERMISSION_DENIED` | Niagara `PermissionException` из любого мутирующего вызова внутри gateway-обёрнутого tool'а. `data{user, ord, operation, tool, detail}`. |
| `-32011` | `ERR_USER_NOT_FOUND` | Tool с `requiresUserContext=true` вызван с Bearer, резолвящимся в apiToken (service identity), а не в BUser. `data{tool, requiresUserContext: true}`. |

---

## Workbench polish и feature dump (v0.4.1)

UX-полировка перед реальным walkthrough-тестированием. Без
breaking changes.

### Read-only счётчики на Property Sheet

`BMcpPlatformService` показывает 4 SUMMARY+READONLY-поля — оператор
открывает сервис в Workbench и сразу видит runtime-статистику без
вызова диагностического tool:

| Property | Источник |
|---|---|
| `toolCount` | `ToolRegistry.all().size()` (36 в v0.4.1) |
| `resourceCount` | `ResourceRegistry.all().size()` (6) |
| `promptCount` | `PromptRegistry.all().size()` (7) |
| `sessionCount` | `McpSessions.activeCount()` (комбинированно SSE + Streamable) |

`sessionCount` обновляется немедленно при каждом create/remove
сессии — без polling, без новых потоков. Раздельный счётчик по
транспортам отложен до v0.5 вместе с расширением McpSessions API
для типизированной итерации.

### `getFeatureDump` tool (36-й)

Статический feature-инвентарь работающего сервера — для AI-клиентов
на discovery и операторов копирующих MCP-ответы. Два формата:

- `text` (default) — человекочитаемый баннер с tools по категориям,
  resources, prompts, transport-флагами, knowledge-статистикой,
  sessions, health и 9 impl-defined JSON-RPC error-кодами
  (-32001..-32009) с расшифровкой.
- `json` — те же данные структурированно для программного потребления.

Парный к v0.4 `getDiagnosticDump` (динамическое состояние) — этот
tool про статический каталог фич.

---

## Безопасность

### Что есть

- Bearer-token аутентификация на каждом запросе.
- Fail-secure: пустой `apiToken` → `401`.
- `UNAUTHENTICATED_ACCESS` permission объявлен явно с `purposeKey` для аудита.
- `service.enabled=false` → `503` на всех endpoints.
- **Bounded SSE-очередь** (1000 сообщений на сессию). При переполнении сообщение дропается и пишется warning в `bcLog` — heap не разрастается из-за медленного клиента.
- **10-секундный timeout** на `bqlQuery` cursor iteration. Усечённые результаты помечены в footer ответа.

### Чего нет (известные ограничения v0.1.0)

| Гэп | Mitigation сейчас |
|---|---|
| Токен хранится в plain `String`-property в `.bog` | Workbench-доступ к сервису = токен виден. Использовать жёсткий ACL на сам сервис. |
| Нет RBAC по ord — токен = full write access | Использовать только в trusted-окружении; держать токен в secret-store клиента. |
| Нет audit log для `writePoint` | Через `BAuditService` добавить в будущей версии. |
| `CORS = *` в `WEB-INF/web.xml` | Если on-prem — сузить до известных origin'ов. |
| Нет rate-limit | Не пускать ненадёжных клиентов; держать за reverse-proxy если из интернета. |
| Нет automatic token rotation | Менять руками. |

### Threat model в одной строке

> **Кто получил токен — получил права root-engineer'а на станции.**

Используйте только за TLS, токен генерируйте как UUID v4, держите станцию за сетевым ACL, не публикуйте endpoint в интернет без отдельного reverse-proxy с auth/rate-limit.

Подробный разбор рисков и предлагаемые улучшения — в [`_CODE_REVIEW.md`](./_CODE_REVIEW.md).

---

## Структура репозитория

```
niagaramcp/
├── README.md                        ← English (main)
├── README.ru.md                     ← вы здесь
├── LICENSE                          ← Apache 2.0
├── CHANGELOG.md
├── _CODE_REVIEW.md                  ← разбор архитектуры и рисков
├── _SMOKE_TEST.md                   ← результаты smoke-теста v0.1.0
├── RELEASE.md                       ← git/release команды
├── .gitignore
└── niagaramcp-rt/                      ← runtime-модуль Niagara
    ├── niagaramcp-rt.gradle.kts
    ├── module.palette
    ├── module.lexicon
    ├── module-permissions.xml
    ├── WEB-INF/web.xml
    └── src/
        └── com/niagaramcp/
            ├── json/                   (embedded JSON parser)
            └── server/
                ├── BMcpPlatformService.java
                ├── McpProtocol.java
                ├── McpServlet.java
                ├── Session.java
                ├── SseSession.java
                ├── StreamableSession.java
                ├── McpSessions.java
                ├── ToolRegistry.java
                └── tools/
                    ├── Tool.java
                    ├── EchoTool.java
                    ├── ListChildrenTool.java
                    ├── ReadPointTool.java
                    ├── WritePointTool.java
                    └── BqlQueryTool.java
```

---

## Roadmap (post-v0.1)

- **v0.2**: `BPasswordPassword` для `apiToken`; `BAuditService` для `writePoint`/`bqlQuery`; полная observability story (перенести `System.out.println` на `BLog`/`Logger`).
- **v0.3**: RBAC по ord-pattern; раздельные read-only / write токены.
- **v0.4**: shading `com.niagaramcp.json` → `com.niagaramcp.server.json` для устранения classloader-конфликтов на MCS-станциях.
- **v0.5**: shaded jar без embedded JSON; зависимость на `nre.jar`-classpath.

---

## Лицензия

Apache License 2.0. См. [`LICENSE`](./LICENSE).

Embedded `com.niagaramcp.json.*` — based on [stleary/JSON-java](https://github.com/stleary/JSON-java) (Public Domain).
