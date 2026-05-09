# Release v0.1.0 — пошаговая инструкция

Репозиторий: одна папка `niagaramcp/`, она и есть корень будущего git-проекта. Все команды выполнять из неё:

```powershell
cd C:\Users\User\Niagara4.15\TridiumEMEA\MCP_Server\niagaramcp
```

⚠ **Перед `git init` убедись**, что:
- сборка прошла (`niagaramcp-rt\build\libs\niagaramcp-rt.jar` существует — это для smoke-теста, в репо не попадёт по `.gitignore`);
- `_CODE_REVIEW.md` и `_SMOKE_TEST.md` дочитаны и устраивают;
- `LICENSE` соответствует выбору (по умолчанию Apache 2.0; если хочется MIT/GPL/BSD — заменить файл до коммита).

---

## 1. Инициализация и первый коммит

```bash
git init -b main
git add .
git status            # убедиться что build/, .gradle/, *.jar НЕ в индексе
git commit -m "Initial release v0.1.0

MCP (Model Context Protocol) server for Tridium Niagara 4.15.3.x stations.

- BMcpPlatformService (BIService) with bearer-token auth
- McpServlet with SSE + JSON-RPC 2.0 messages endpoint
- 5 tools: echo, listChildren, readPoint, writePoint, bqlQuery
- Embedded com.niagaramcp.json parser
- Apache 2.0 licensed

See _CODE_REVIEW.md and _SMOKE_TEST.md for v0.1.0 status and known limitations."
```

## 2. Тег и remote

```bash
git tag -a v0.1.0 -m "v0.1.0 — initial public release"

# подставь свой URL
git remote add origin <https://github.com/OWNER/REPO.git>

git push -u origin main
git push origin v0.1.0
```

## 3. Создать GitHub Release

В UI GitHub: **Releases → Draft a new release → выбрать тег `v0.1.0`**.

**Title:** `v0.1.0 — initial public release`

**Description** (можно вставить как есть):

```markdown
First public release of **niagaramcp** — MCP (Model Context Protocol) server module
for Tridium Niagara 4.15.3.x stations.

## What's in this release

- `BMcpPlatformService` — Niagara `BIService` with `enabled`, `apiToken`,
  `sseHeartbeatSec` slots.
- `McpServlet` — SSE + JSON-RPC 2.0 endpoint with bearer-token auth.
- 5 tools: `echo`, `listChildren`, `readPoint`, `writePoint`, `bqlQuery`.
- Embedded `com.niagaramcp.json.*` JSON parser.
- Module signed with Niagara dev-cert. **For production use, re-sign with
  your enterprise cert.**

## Compatibility

- Tridium Niagara **4.15.3.x** (tested on 4.15.3.28).
- Depends on stock `baja`, `control-rt`, `web-rt` modules.
- Java 8 to build.

## ⚠ Security

This is an **early release**. The `apiToken` grants effectively root engineer
access to the station — no RBAC, no audit trail, no rate-limit, no token
rotation. Do not expose to untrusted networks. See [`_CODE_REVIEW.md`](./_CODE_REVIEW.md)
for full threat model.

## Install

1. `gradlew :niagaramcp-rt:jar --configure-on-demand`
2. Copy `niagaramcp-rt/build/libs/niagaramcp-rt.jar` to `$niagara_home/modules/`.
3. Restart station, drag `McpPlatformService` from the `niagaramcp` palette
   into `Services/`, set `apiToken` to a fresh UUID.

Full instructions in [`README.md`](./README.md).

## Changelog

See [`CHANGELOG.md`](./CHANGELOG.md).
```

Опционально приложить готовый jar как **release asset** (загрузить
`niagaramcp-rt/build/libs/niagaramcp-rt.jar` в форму).

---

## Что НЕ должно попасть в репо

`.gitignore` уже это покрывает, но проверь руками перед `git commit`:

```bash
git status --ignored | head
```

Не коммить:
- `niagaramcp-rt/build/`, `niagaramcp-rt/.gradle/`
- `*.jar` (включая собранный `niagaramcp-rt.jar` — он в release asset, не в git)
- `*.jks`, `*.keystore`, `keystore.properties` (signing material)
- `local/my-niagara.gradle*`

---

## После пуша — что проверить на GitHub

- README.md рендерится корректно (RU + EN abstract в начале).
- `LICENSE` распознан как Apache-2.0 в About-блоке.
- В описании репо одной строкой: `MCP (Model Context Protocol) server for Tridium Niagara stations`.
- Тэг `v0.1.0` виден в Releases.
- Если хочешь — добавить Topics: `niagara`, `tridium`, `mcp`, `model-context-protocol`,
  `building-automation`, `iot`, `java`.
