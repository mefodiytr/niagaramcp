# Smoke Test — niagaramcp-rt v0.1.0

Дата: 2026-05-09
Niagara version: 4.15.3.28
Host: WIN-BTA9VOGLJD5
Tester: build/install verification only (без запуска station)

---

## Результат: **PASSED** (manifest-level)
Runtime-проверка (старт station + MCP-запрос) пропущена — см. раздел «Что не проверено».

---

## Шаги и результаты

### 1. Деплой jar

```powershell
Copy-Item niagaramcp-rt\build\libs\niagaramcp-rt.jar `
          C:\Niagara\Niagara-4.15.3.28\modules\ -Force
```

| Параметр | Значение |
|---|---|
| Source | `MCP_Server\niagaramcp\niagaramcp-rt\build\libs\niagaramcp-rt.jar` |
| Destination | `C:\Niagara\Niagara-4.15.3.28\modules\niagaramcp-rt.jar` |
| Размер | **117 008 байт** |
| Mtime после копирования | 2026-05-09 09:18 |

✓ Файл скопирован успешно.

### 2. Проверка подписи

```bash
jarsigner -verify -verbose -certs C:\Niagara\Niagara-4.15.3.28\modules\niagaramcp-rt.jar
```

**Результат**: `jar verified.` — подпись валидна.

| Атрибут | Значение |
|---|---|
| Алгоритм | SHA256withRSA, 3072-bit |
| Signer DN | `CN=User@WIN-BTA9VOGLJD5(Niagara4Modules), OU=For Development Purposes Only Do Not Distribute, O=Tridium, L=Richmond, ST=Virginia, C=US` |
| Действителен до | ~2027-02-28 |
| Цепочка | self-signed (Niagara dev cert) |
| Timestamp | отсутствует |

**Предупреждения** (некритичные для dev-станции):
- `certificate chain is invalid` — self-signed, ожидаемо для dev-сборки.
- `signer certificate is self-signed` — ожидаемо.
- `signatures that do not include a timestamp` — после 2027-02-28 jar потеряет валидность; для production-релиза подписать timestamping authority.

**Подпись для v0.1.0**: jar подписан стандартным Niagara dev-cert (`NIAGARA4.RSA`, см. таблицу выше). Production-deployment-у нужно перенастроить `niagara-signing` plugin на enterprise-cert (Tridium-issued или ваш организационный) — иначе на станции с `signed-only` policy модуль не загрузится. На станциях с дефолтной dev-policy (доверяет своему `Niagara4Modules` cert) jar загружается из коробки.

### 3. Проверка manifest и module.xml

`META-INF/MANIFEST.MF`:
```
Manifest-Version: 1.0
Implementation-Vendor: bc
Implementation-Version: 1.0
Sealed: true
```
✓ Все entries имеют `SHA-256-Digest`.

`META-INF/module.xml`:
```xml
<module name="niagaramcp-rt" moduleName="niagaramcp" runtimeProfile="rt"
        vendor="bc" vendorVersion="1.0"
        preferredSymbol="niagaramcp" nre="true" autoload="true" installable="true">
  <dependencies>
    <dependency name="baja"       vendor="Tridium" vendorVersion="4.15"/>
    <dependency name="control-rt" vendor="Tridium" vendorVersion="4.15"/>
    <dependency name="web-rt"     vendor="Tridium" vendorVersion="4.15"/>
  </dependencies>
  <types>
    <type class="com.niagaramcp.server.BMcpPlatformService" name="McpPlatformService"/>
  </types>
  <permissions>
    <niagara-permission-groups type="station">
      <req-permission>
        <name>NETWORK_COMMUNICATION</name>
        ...
      </req-permission>
      <req-permission>
        <name>UNAUTHENTICATED_ACCESS</name>
        ...
      </req-permission>
    </niagara-permission-groups>
  </permissions>
</module>
```
✓ Тип `McpPlatformService` зарегистрирован.
✓ Обе нужные permissions объявлены с `purposeKey`.
✓ `runtimeProfile="rt"` корректен.

### 4. Проверка зависимостей

Установленные на хосте версии Niagara-модулей (declared min `4.15`):

| Модуль | Установленная версия | Совместимость |
|---|---|---|
| `baja` | 4.15.3.28 | ✓ |
| `control-rt` | 4.15.3.28 | ✓ |
| `web-rt` | 4.15.3.28 | ✓ |

✓ Все зависимости разрешаются. Минор-версия `4.15.3.28` ≥ объявленной `4.15`.

### 5. Содержимое jar

```
META-INF/MANIFEST.MF
META-INF/NIAGARA4.SF
META-INF/NIAGARA4.RSA
META-INF/module.xml
WEB-INF/web.xml             ← servlet config, в корне jar (как ожидается)
module.palette              ← в корне jar
niagaramcp-rt.lexicon          ← переименован из module.lexicon
com/niagaramcp/server/*.class       (8 файлов)
com/niagaramcp/server/tools/*.class (6 файлов: Echo, ListChildren, ReadPoint, WritePoint, BqlQuery + Tool interface)
com/niagaramcp/json/*.class         (24 файла, embedded library)
```

✓ `WEB-INF/web.xml` попал в jar (sourceSets-конфиг сработал).
✓ `module.palette` в корне jar.
✓ Lexicon корректно переименован в `<moduleName>-rt.lexicon`.

---

## Что не проверено (skipped)

### Запуск тестовой station

На хосте есть `C:\Users\User\Niagara4.15\TridiumEMEA\stations\test415\config.bog`. Старт станции из smoke-test'а пропущен потому что:
- Может конфликтовать с уже работающей станцией пользователя (порты, lock-файлы).
- Без знания credentials и порта станции невозможно автоматически выполнить тестовый MCP-запрос.
- Smoke-test не должен оставлять побочных эффектов в station-config.

**Чтобы прогнать runtime-проверку вручную**, нужно:

1. Открыть Workbench, подключиться к `test415`.
2. Из палитры `niagaramcp` перетащить `McpPlatformService` под `Services/`.
3. На сервисе: `enabled=true`, `apiToken=<любой-uuid>`, `showLog=true`.
4. Сохранить станцию, перезапустить.
5. Проверить в `Spy → console.log` строку `niagaramcp BMcpPlatformService serviceStarted`.
6. Из CLI:
   ```bash
   curl -N -H "Authorization: Bearer <token>" \
        https://<station>:5011/niagaramcp/sse
   ```
   Ожидать `event: endpoint` + `data: /niagaramcp/messages?sessionId=…`.
7. В отдельном окне — `tools/list` и `tools/call echo` с тем же sessionId.

### Behaviour-тесты конкретных tools

`ReadPointTool`, `WritePointTool`, `BqlQueryTool` зависят от наличия реальных point'ов в станции. Проверены только сборкой/импортами, не runtime-поведением.

### CrossOriginFilter

Класс `org.eclipse.jetty.servlets.CrossOriginFilter` ожидается из jetty, входящего в `web-rt`. Не проверял отдельно через `javap`. На уровне web.xml корректно объявлен.

---

## Резюме smoke-теста

| Критерий | Статус |
|---|---|
| Сборка | ✓ PASSED |
| Подпись валидна | ✓ PASSED (с предупреждениями self-signed) |
| Manifest корректен | ✓ PASSED |
| module.xml тип + permissions | ✓ PASSED |
| WEB-INF в jar | ✓ PASSED |
| Зависимости резолвятся | ✓ PASSED |
| Запуск station | ⚠ SKIPPED (требует ручного теста) |
| Runtime MCP-запрос | ⚠ SKIPPED (требует станцию + клиента) |

**Готов к мерджу/тегу v0.1.0** для целевой аудитории «dev-станция, тот же хост, тот же дев-сертификат». Для production-распространения — сменить сертификат подписи (см. _CODE_REVIEW.md §5).

---

# v0.2.0 — Streamable HTTP smoke test

После применения v0.2.0-jar (та же процедура deploy + jarsigner -verify
из §1-§2 выше) прогнать следующий runbook против рабочей station.
Предполагается: `apiToken` уже установлен на сервисе, сервис `enabled=true`.

```bash
TOKEN=<apiToken-из-сервиса>
HOST=https://<station-host>
```

### Шаг 1. `initialize` без `Mcp-Session-Id` → 200 + новый id

```bash
curl -sS -D - -H "Authorization: Bearer $TOKEN" \
     -H "Content-Type: application/json" \
     "$HOST/niagaramcp/mcp" \
     -d '{"jsonrpc":"2.0","id":1,"method":"initialize"}'
```

Ожидать:
- HTTP `200 OK`
- Response header `Mcp-Session-Id: <uuid>`
- Body: `{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18","capabilities":{"tools":{}},"serverInfo":{"name":"niagaramcp","version":"1.0.0"}}}`

Сохранить `Mcp-Session-Id` в переменную `SID`.

### Шаг 2. `tools/list` с этим id → массив из 5 tools

```bash
curl -sS -H "Authorization: Bearer $TOKEN" \
     -H "Content-Type: application/json" \
     -H "Mcp-Session-Id: $SID" \
     "$HOST/niagaramcp/mcp" \
     -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'
```

Ожидать `result.tools` длиной 5: `echo`, `listChildren`, `readPoint`,
`writePoint`, `bqlQuery`. Каждый с непустым `inputSchema`.

### Шаг 3. `tools/call` echo → round-trip

```bash
curl -sS -H "Authorization: Bearer $TOKEN" \
     -H "Content-Type: application/json" \
     -H "Mcp-Session-Id: $SID" \
     "$HOST/niagaramcp/mcp" \
     -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"echo","arguments":{"msg":"ping"}}}'
```

Ожидать `result.content[0].text == "ping"`, `result.isError == false`.

### Шаг 4. `DELETE /mcp` → 204

```bash
curl -sS -i -X DELETE -H "Authorization: Bearer $TOKEN" \
     -H "Mcp-Session-Id: $SID" \
     "$HOST/niagaramcp/mcp"
```

Ожидать `HTTP/1.1 204 No Content` без тела.

### Шаг 5. POST с уже удалённым id → 404

```bash
curl -sS -i -H "Authorization: Bearer $TOKEN" \
     -H "Content-Type: application/json" \
     -H "Mcp-Session-Id: $SID" \
     "$HOST/niagaramcp/mcp" \
     -d '{"jsonrpc":"2.0","id":99,"method":"ping"}'
```

Ожидать `HTTP/1.1 404 Not Found` с body `Session not found or expired: <SID>`.

### Шаг 6. SSE-flow (legacy) — не сломан

Прогнать прежний v0.1.0 runbook (§«Что не проверено» → шаги 6-7 выше)
для `/sse` + `/messages?sessionId=…` — должен работать как раньше,
бит-в-бит.

### Чек-лист

| # | Что проверяем | Статус |
|---|---|---|
| 1 | initialize без id → 200 + Mcp-Session-Id | □ |
| 2 | tools/list с id → 5 tools | □ |
| 3 | tools/call echo → round-trip | □ |
| 4 | DELETE → 204 | □ |
| 5 | POST с удалённым id → 404 | □ |
| 6 | Legacy /sse + /messages по-прежнему работают | □ |

Любой провал = остановиться и зафлажить, не продолжать релиз.

---

# v0.3.0 — Semantic layer smoke test

После v0.3.0 jar deploy:

```bash
TOKEN=<apiToken>
HOST=https://<station-host>
SID=$(curl -sS -D - -H "Authorization: Bearer $TOKEN" \
       -H "Content-Type: application/json" \
       "$HOST/niagaramcp/mcp" \
       -d '{"jsonrpc":"2.0","id":1,"method":"initialize"}' \
     | tr -d '\r' | awk -F': ' '/^Mcp-Session-Id/{print $2}')
hdr() { printf '"Authorization: Bearer %s" "Content-Type: application/json" "Mcp-Session-Id: %s"' "$TOKEN" "$SID"; }
```

### Шаг 1. capabilities включают resources + prompts

`initialize` ответ должен содержать:
```json
"capabilities": { "tools": {}, "resources": {}, "prompts": {} }
```

### Шаг 2. tools/list возвращает 28 entries

```bash
curl -sS -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
     -H "Mcp-Session-Id: $SID" "$HOST/niagaramcp/mcp" \
     -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}' | jq '.result.tools | length'
```

Ожидать: `28`.

### Шаг 3. resources/list возвращает 2 static + 4 templated

```bash
curl ... -d '{"jsonrpc":"2.0","id":3,"method":"resources/list"}'
curl ... -d '{"jsonrpc":"2.0","id":4,"method":"resources/templates/list"}'
```

Ожидать `result.resources` длиной 3 (overview, kinds/catalog, samples/standard-types) и `result.resourceTemplates` длиной 3 (equipment, spaces, standalone-points).

### Шаг 4. resources/read niagara://overview

```bash
curl ... -d '{"jsonrpc":"2.0","id":5,"method":"resources/read","params":{"uri":"niagara://overview"}}'
```

Ожидать `result.contents[0].mimeType=="application/json"` + `text` со счётчиками spaceCount/equipmentTypeCount/equipmentCount/standalonePointCount.

### Шаг 5. prompts/list возвращает 7 prompts

```bash
curl ... -d '{"jsonrpc":"2.0","id":6,"method":"prompts/list"}' | jq '.result.prompts | length'
```

Ожидать: `7`.

### Шаг 6. importKnowledge source='sample'

```bash
curl ... -d '{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{
  "name":"importKnowledge",
  "arguments":{"source":"sample","mode":"merge"}}}'
```

Ожидать в content.text JSON `{"ok":true,"mode":"merge","added":5,"skipped":0}` (5 = ahu/rooftop/chiller/pump/fcu).

### Шаг 7. createSpace + getKnowledgeSummary roundtrip

```bash
curl ... -d '{"jsonrpc":"2.0","id":8,"method":"tools/call","params":{
  "name":"createSpace",
  "arguments":{"id":"test-zone","name":"Test","type":"zone"}}}'

curl ... -d '{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{
  "name":"getKnowledgeSummary","arguments":{}}}'
```

Ожидать spaceCount=1.

### Шаг 8. readHistory против real point

Если на станции есть точка с history extension:

```bash
curl ... -d '{"jsonrpc":"2.0","id":10,"method":"tools/call","params":{
  "name":"readHistory",
  "arguments":{
    "ord":"station:|slot:/Drivers/<your-point>",
    "from":"2026-05-08T00:00:00Z",
    "aggregation":"avg"}}}'
```

Ожидать numeric `avg`/`min`/`max`/`count` или массив records без агрегации.

### Шаг 9. getActiveAlarms

```bash
curl ... -d '{"jsonrpc":"2.0","id":11,"method":"tools/call","params":{
  "name":"getActiveAlarms","arguments":{"limit":10}}}'
```

Ожидать массив из ≤10 alarm records (или пустой если на станции тихо).

### Шаг 10. v0.2 endpoints НЕ сломаны

Прогнать v0.2.0 runbook (выше) — initialize/tools/list/echo/DELETE — должно всё работать.

### Чек-лист v0.3.0

| # | Проверка | Статус |
|---|---|---|
| 1 | capabilities content {tools,resources,prompts} | □ |
| 2 | tools/list = 28 | □ |
| 3 | resources/list = 3, templates = 3 | □ |
| 4 | resources/read overview | □ |
| 5 | prompts/list = 7 | □ |
| 6 | importKnowledge sample → added 5 | □ |
| 7 | createSpace + summary roundtrip | □ |
| 8 | readHistory против реальной точки | □ |
| 9 | getActiveAlarms | □ |
| 10 | v0.2.0 SSE + Streamable не сломаны | □ |

Любой провал — стоп, разбор.
