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
