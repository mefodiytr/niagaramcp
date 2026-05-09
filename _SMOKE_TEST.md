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

**Сравнение с оригиналом**: исходный `niagaramcp-rt.jar` был подписан `BCC.RSA` (niagaramcp enterprise CA). Наша сборка подписана стандартным Niagara dev-cert (`NIAGARA4.RSA`). Это различие важно:
- На станции с `signed-only` policy и доверенным BCC CA — наш jar **не загрузится**.
- На станции с дефолтной dev-policy (доверяет своему `Niagara4Modules` cert) — загрузится.

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
ru/bccontrol/niagaramcp/*.class       (8 файлов)
ru/bccontrol/niagaramcp/tools/*.class (5 файлов: Echo, ListChildren, ReadPoint, WritePoint, BqlQuery + Tool interface)
ru/bccontrol/json/*.class          (26 файлов, embedded library)
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
