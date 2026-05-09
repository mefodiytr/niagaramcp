# Code Review — platMcp-rt

Дата: 2026-05-09
Объём ревью: `platMcp-rt/src/ru/bccontrol/platMcp/**`, `platMcp-rt.gradle.kts`, `module-permissions.xml`, `module.palette`, `module.lexicon`, `niagara-module.xml`, `WEB-INF/web.xml`. Embedded `ru.bccontrol.json.*` (1k+ строк) рассматривается как третья сторона и в детальный обзор не включён.

---

## 1. Архитектура и поток данных

```
HTTP client (MCP)
   │  GET  /sse                                  ▶  McpServlet.handleSse()
   │  POST /messages?sessionId=…  body=JSON-RPC  ▶  McpServlet.handleMessage()
   ▼
McpServlet (UnauthenticatedServlet, bearer-auth in checkAuth)
   ├── McpSession + McpSessions (UUID → BlockingQueue<String>, SSE outgoing)
   └── McpProtocol.handle(request, registry, session)
          ├── initialize / ping / tools/list / tools/call
          └── ToolRegistry (LinkedHashMap<String,Tool>)
                ├── EchoTool          — диагностика
                ├── ListChildrenTool  — обход slot-tree через BOrd + getChildComponents()
                ├── ReadPointTool     — BControlPoint.get("out") + BFacets
                ├── WritePointTool    — BIWritablePoint.getInStatusValue(priority)
                └── BqlQueryTool      — BITable + TableCursor
BMcpPlatformService (BIService): держит singleton INSTANCE, регистрирует все Tool-ы в serviceStarted()
```

Точка входа Niagara — `BMcpPlatformService` (зарегистрирован в `META-INF/module.xml` через `@NiagaraType`). Инстанцируется из палитры (`module.palette`), запускается станцией как `BIService`. `McpServlet` хостится через `WEB-INF/web.xml` (`url-pattern=/*`) и общается с сервисом через статический `BMcpPlatformService.getRegistry()`/`isEnabled()`/`apiToken()`.

---

## 2. Что сделано хорошо

- **Чёткое слоение**: транспорт (Servlet) ↔ протокол (McpProtocol) ↔ домен (Tool). Каждая абстракция тестируется отдельно.
- **`Tool` как plug-in interface** с self-describing `schemaJson()` — добавление нового tool не требует правок McpProtocol.
- **Корректный выбор Niagara API**: `BOrd.make().get()`, `BControlPoint.get("out")`, `BFacets.gets()` — каноничные пути для 4.x.
- **Concurrency-safe sessions**: `ConcurrentHashMap` + `LinkedBlockingQueue` + sentinel `__CLOSE__` — стандартный pattern для SSE без рискованных wait/notify.
- **Heartbeat в SSE** (`: ping\n\n` через `poll(timeoutMs)`) — корректно держит keep-alive для прокси/балансировщиков.
- **Type-safe writable points**: проверка `instanceof BNumericWritable/BBooleanWritable/BEnumWritable/BStringWritable` с понятным fallback-исключением.
- **JSON-RPC 2.0 совместимость**: ID-mirroring, отделение notifications от запросов, корректные коды ошибок (-32700/-32600/-32601/-32602/-32603).
- **`SuppressWarnings("unchecked")`** на `BqlQueryTool.call()` для cast'а `BITable<BObject>` — явно, локально, не подавляет другие проверки.
- **`module-permissions.xml`** декларирует обе нужные разрешения с осмысленными `purposeKey` (важно для аудита станции).

---

## 3. Замечания и риски

### 3.1 SSE: `handleSse()` и lifecycle сессий

`session.poll(heartbeatMs)` блокирует тред servlet-контейнера. При:
- **`Thread.interrupt()`** (например, при остановке станции) — ловим `InterruptedException`, `Thread.currentThread().interrupt()` восстанавливает флаг, выходим из `while`, попадаем в `finally { McpSessions.remove(...) }`. ✓ корректно.
- **Разрыве TCP**: проверка `w.checkError()` срабатывает только после следующей записи. Если клиент молчит и heartbeat не пишется (например, между двумя `: ping`), детект разрыва задерживается до `heartbeatMs`. На дефолте 25 сек это ОК, на больших значениях — лишний поток в `WAITING`.
- **`closeAll()` из `serviceStopped()`**: ставит `__CLOSE__` в очередь — корректно, цикл выходит.
- **Risk**: если servlet-контейнер сам убил тред без `interrupt()` (timeout configuration), `McpSessions.remove()` всё равно сработает через `finally`. ✓

**Дырка**: `outgoing` BlockingQueue без bounded capacity. Если клиент медленный, а tools/call идёт лавиной — heap grow без предела. Mitigation: `LinkedBlockingQueue<>(N)` с `offer()` без timeout уже сейчас отбрасывает при переполнении (но с `Integer.MAX_VALUE` это никогда не случится).

### 3.2 McpServlet: path matching

- `req.getPathInfo()` возвращает уже декодированный path (без query string).
- `stripSlash()` снимает только ведущий `/`. `equals("sse")` точечно сравнивает.
- Попытки `/sse/foo`, `/SSE`, `/sse?x=1`, `/sse;jsessionid=…` → `getPathInfo()` отдаст что-то ≠ `"sse"` → 404. ✓ безопасно от path traversal.
- `sessionId` берётся через `req.getParameter()` — нормализован контейнером, но как ключ HashMap безопасен (не идёт в FS/SQL).

### 3.3 `apiToken` как plain `String` property

- Хранится в `.bog` файле станции в открытом виде.
- Любой workbench-юзер с правом «view service» прочитает токен.
- В `getApiToken()` не маскируется (и не должен — нужен для compare).
- Лучшая модель: `BPasswordPassword`, либо `BString` с `flags=READONLY+HIDDEN` и admin-only setter.
- **На MVP уровне**: документировать в README как «токен = эквивалент admin-пароля станции, держать вне UI и за TLS».

### 3.4 `WritePointTool`: priority 1..16 без RBAC

- Любой клиент с валидным токеном может писать @1 (override-everything). Бизнес-логика станции (interlocks, schedules) обходится.
- Нет per-ord allowlist/denylist, нет различия read/write токенов.
- Mitigation на уровне кода нет — только инфраструктурно (отдельный токен на станцию + сетевой ACL). Должно быть в README большим жирным текстом.
- Долгосрочно: ввести `BMcpPolicy` slot — карту regexp на ord → разрешённые priorities.

### 3.5 `BqlQueryTool`: limit vs cursor cost

- `limit` ограничивает только количество строк, прочитанных в ответ. Сам `BOrd.make(query).get()` исполняется до того, как мы видим первый row, и это блокирующий вызов в http-thread.
- Если запрос — `select * from history:HistoryRecord limit 100k` без BQL-side limit — Niagara может построить полный cursor в памяти. Наш `break` спасает от OOM на нашей стороне, но станция уже заплатила.
- Нет таймаута на cursor.
- **Mitigation**: документировать рекомендацию использовать BQL `limit` в самом запросе; добавить `Thread.interrupt()`-aware wrapping в будущей версии.

### 3.6 Embedded `ru.bccontrol.json` — потенциальный ClassLoader-конфликт

- Эти классы встроены в jar (60 .class файлов, ~110 KB).
- Если станция уже содержит `nre.jar` или другой модуль с теми же классами в `ru.bccontrol.json` — Niagara использует child-first ClassLoader для модулей, поэтому будут наши копии. **НО**: если код встроенного MCS-модуля передаёт `ru.bccontrol.json.JSONObject` через границу модуля, получим `ClassCastException` (один и тот же FQN, разные ClassLoader = разные `Class<?>`).
- Mitigation для open-source: переименовать пакет в `ru.bccontrol.platMcp.json` (shaded). Не критично если модуль ставится в чистую станцию.

### 3.7 `bcLog()` через `System.out`

- Пишет в stdout процесса станции. В nre/wrapper-окружении уходит в `console.log`, в Workbench Console этого не видно.
- Должно быть `Logger logger = Logger.getLogger("platMcp")` (java.util.logging) или `BLog`/`Logger.getLogger("station")` чтобы попадало в `system.log` и было фильтруемо через Spy → log setup.
- Влияние на работу нулевое, но при прод-инциденте отладка усложняется.

### 3.8 `BMcpPlatformService.INSTANCE` как static

- Если в станции окажется два `McpPlatformService` (например, скопированы в разные Folders), второй `serviceStarted()` перезапишет `INSTANCE` → первый «осиротеет», его `apiToken/sseHeartbeatSec` больше не используются, но он всё ещё помечен `running`.
- `closeAll()` в `serviceStopped()` второго инстанса убьёт сессии, инициированные первым (общий `McpSessions.SESSIONS`).
- Mitigation на уровне модели: явно `enforce singleton` через `containerSlot` + проверку в `started()`, или сделать `INSTANCE` per-Service-instance и не использовать static.

### 3.9 CORS `*`

- `WEB-INF/web.xml` ставит `allowedOrigins=*` в `CrossOriginFilter`. Для prod это OK только если за TLS + bearer-auth (что у нас и есть), но при компрометации токена браузерный JS с любого origin'а тоже может вызывать.
- Если планируется SaaS-интеграция — оставить. Если on-prem — стоит сузить до известного origin'а через property.

---

## 4. TODO / FIXME / dead code

| Где | Что | Серьёзность |
|---|---|---|
| `BMcpPlatformService.nativesLoaded` | `public static boolean`, всегда `false`, нигде не читается | Удалить |
| `BMcpPlatformService.test` action + `doTest()` метод | Просто логирует количество сессий, остаток отладки | Удалить или явно пометить `@debug` |
| `McpSession.take()` | public метод, не используется (только `poll(timeoutMs)`) | Удалить или `@Deprecated` |
| `McpSession.isInitialized()` | геттер не используется (есть только setter `markInitialized`) | Подумать: либо удалить, либо реально пользоваться (отбрасывать запросы до initialize) |
| `McpSession.SENTINEL_CLOSE` | константа `package-private`, McpServlet сравнивает с литералом `"__CLOSE__"` | Унифицировать, использовать константу |
| `McpProtocol.SERVER_NAME/SERVER_VERSION` | дублируют `"platMcp"` / `"1.0.0"` в `buildInitializeResult()` | Уже извлечено в константу, но `1.0.0` хардкоден — должен подтягиваться из gradle/manifest |
| `EchoTool.description` | "Вернуть переданное сообщение..." на русском | Лексикон не используется, локализация будет сложнее |

Явных `TODO`/`FIXME`-комментариев в исходниках нет.

---

## 5. Совместимость с Tridium Niagara 4.15.3.28

**Подтверждено сборкой/инспекцией classpath:**

- `javax.baja.sys.BFacets` — присутствует, импорт корректен. `javax.baja.facets.BFacets` в 4.15 не существует.
- `javax.baja.status.BStatusValue.getValueValue()` — есть в `baja.jar`. Возвращает `BValue`. ✓
- `javax.baja.web.servlets.UnauthenticatedServlet` — в `web-rt.jar`, `extends javax.servlet.http.HttpServlet`. ✓
- `javax.baja.sys.BIService` — интерфейс с `serviceStarted()` / `serviceStopped()` / `getServiceTypes()`. ✓
- `javax.baja.control.{BControlPoint, BIWritablePoint, BNumericWritable, BBooleanWritable, BEnumWritable, BStringWritable}` — `control-rt.jar`. ✓
- `javax.baja.collection.{BITable, ColumnList, TableCursor, Column}` — `baja.jar`. ✓
- `javax.servlet-api 3.1.0` — в `bin/ext`, добавлен как `compileOnly` в gradle.kts.
- Аннотации (`@NiagaraType`, `@NiagaraProperties`, `@NiagaraAction`) обработаны annotation processor'ом — `META-INF/module.xml` после билда содержит `<type class="ru.bccontrol.platMcp.BMcpPlatformService" name="McpPlatformService"/>`. ✓

**Под вопросом / не проверено runtime:**

- **Подпись модуля.** Сборка использует `niagara-signing` plugin → подпись в `META-INF/NIAGARA4.SF` / `NIAGARA4.RSA` (как в `testbc-rt.jar`). Оригинальный `mcp/` jar был подписан `BCC.RSA` — возможно, нужен enterprise-сертификат BCC для прод-станции, иначе модуль не загрузится в режиме signed-only.
- **Behaviour `BControlPoint.get("out")`** при detached/null point — может вернуть null (мы уже это обрабатываем `outObj == null`).
- **`BOrd.make(query).get()` блокирующий в http-thread** — если станция занята, тред висит. Servlet-контейнер таймаут не настроен. Запрос может «застрять».
- **`org.eclipse.jetty.servlets.CrossOriginFilter`** — должен быть в jetty, который входит в `web-rt`. На 4.15.3.28 ожидается версия Jetty 9.4.58. Если по какой-то причине CrossOriginFilter не на classpath станции, контейнер логирует `ClassNotFoundException` и фильтр не применяется (servlet продолжит работу, но без CORS-заголовков).
- **`BPriorityLevel.make(int)`** существует в 4.15 — нужно убедиться (не проверял отдельно, но компиляция прошла).

---

## 6. Безопасность — модель угроз

### Текущие mitigations

| Угроза | Mitigation |
|---|---|
| Anonymous access | Bearer token обязателен; пустой `apiToken` → `401` (fail-secure) |
| Constant-time? | `expected.equals(token)` — НЕ constant-time, потенциальная side-channel на длине, но для random UUID-токенов нерелевантно |
| Replay | Nope; SSE-сессия привязана к `sessionId`, но JSON-RPC сам не имеет nonce |
| CSRF | `Authorization: Bearer` header всегда требуется → классический CSRF без credentialed cookie не работает |
| Network sniff | Не входит в scope модуля; полагаемся на TLS станции |
| Service disabled | `BMcpPlatformService.isEnabled()` проверяется перед каждым запросом → `503` |

### Гэпы / отсутствующие mitigations

| Угроза | Что нужно бы сделать |
|---|---|
| **Утечка токена через `.bog` / Workbench** | `BPasswordPassword` или access-control на сам сервис |
| **Privilege escalation через writePoint** | RBAC по ord-pattern; раздельные read-only / write-allowed токены |
| **Audit trail** | Сейчас только `bcLog` (если `showLog=true`); должен быть `BAuditService` для каждого `writePoint` и `bqlQuery` |
| **DoS через дорогие запросы** | Нет rate-limit, нет timeout на `BOrd.get()`/cursor |
| **DoS через переполнение `outgoing` queue** | `LinkedBlockingQueue` без capacity → unbounded heap |
| **CORS=`*`** | Ограничить список origin'ов через property |
| **MITM на token rotation** | Токен не ротируется автоматически, нет TTL |
| **Brute-force токена** | UUID-длина токена (если так) защищает энтропией; формат не enforced — пользователь может задать `"123"` |
| **Logs с токеном** | `bcLog` сам по себе не логирует тело запроса/header'ы — пока ОК |

### Угрозы за пределами кода

- Подпись модуля. Если станция настроена на `signed-only`, неподписанный или подписанный неправильным CA jar не загрузится. См. п. 5.
- `UNAUTHENTICATED_ACCESS` permission даёт сервлету полный bypass Niagara-аутентификации — это сознательный design choice. Документировать риски обязательно.

---

## Итог Code Review

**Готовность к OSS-релизу (v0.1.0): УСЛОВНАЯ.**

Блокеров для функциональности нет. Перед публикацией рекомендую:

1. **README обязан явно сказать**: «MCP-токен = root-доступ к станции», «использовать только за TLS», «не давать в untrusted-сети».
2. Решить вопрос по **подписи** (signed by Tridium / self-signed / unsigned dev) — повлияет на инструкцию по деплою.
3. Принять решение по **embedded `ru.bccontrol.json`** — оставить как есть (риск конфликта с MCS-станциями) или shading в `ru.bccontrol.platMcp.json` (отдельный коммит).
4. Удалить dead code (`nativesLoaded`, `test/doTest`).

Ничего не блокирует выпуск как «PoC / for evaluation only», но для production deployment п.1 и п.4 — необходимый минимум, а п.2 определит модель распространения.
