# niagaramcp — MCP server for Tridium Niagara

> **MCP (Model Context Protocol) server for Tridium Niagara stations.**
> A Niagara `BIService` module that exposes a JSON-RPC over HTTP/SSE endpoint, allowing MCP-compatible AI assistants (Claude, ChatGPT, IDEs) to read/write points, walk the slot tree, and run BQL queries against a live station — under bearer-token auth.

🌐 [English](./README.md) · [Русский](./README.ru.md)

---

## What it is and why

**Model Context Protocol (MCP)** is an open protocol from Anthropic for connecting LLM assistants to external systems. The AI client negotiates a set of available *tools* with the server, calls them and receives structured results.

**niagaramcp** turns a Niagara station into such an MCP server. After installing the module and starting `McpPlatformService`, the assistant gets live access to station data:

- read point values and metadata (`readPoint`),
- write to writable points with priority 1..16 (`writePoint`),
- walk the slot tree without BQL (`listChildren`),
- execute BQL queries and receive tabular results (`bqlQuery`),
- check connectivity (`echo`).

Use cases:
- An integrator debugging logic asks the assistant *"which points under Drivers/Modbus are currently in fault"* and gets a live answer.
- The assistant builds a report from a schedule and a BQL query without manual export.
- Self-service tooling: users request a setpoint change in chat, the assistant validates and applies it.

The key advantage: **no MCS, no cloud, no separate gateway.** The station itself is the MCP server.

⚠ **Not production-secure as-is.** See the «Security» section below.

---

## System requirements

- **Tridium Niagara 4.15.3.x** (tested on 4.15.3.28).
- Stock modules `baja-rt`, `control-rt`, `web-rt` (present in any standard Niagara install).
- Java 8 to build. Tridium Gradle plugins from the Niagara distribution (`$niagara_home/etc/m2/repository`).
- Signed module. The build script expects either a Niagara dev cert (`Niagara4Modules`) or your enterprise cert.

Compatibility with Niagara 4.13/4.14 and 4.16+ is unverified — likely just a `vendorVersion` bump in dependencies.

---

## Install

### 1. Build from source

```powershell
cd C:\path\to\niagaramcp
.\..\..\gradlew.bat :niagaramcp-rt:jar --configure-on-demand
```

The jar will appear at `niagaramcp-rt\build\libs\niagaramcp-rt.jar`.

> The `--configure-on-demand` flag is only needed when the parent `TridiumEMEA/` contains foreign broken Gradle projects (e.g. inside `_research/`). For a clean checkout you can drop it.

### 2. Deploy to Niagara

```powershell
Copy-Item niagaramcp-rt\build\libs\niagaramcp-rt.jar `
          C:\Niagara\Niagara-4.15.3.28\modules\ -Force
```

Restart the station.

### 3. Configure

In Workbench:

1. Open the `niagaramcp` palette.
2. Drag **`McpPlatformService`** into `Services/` of the target station.
3. On the service set:
   - `enabled` = `true`
   - `apiToken` = a cryptographically random UUID (≥128 bits of entropy). **This is equivalent to a station admin password.**
   - `sseHeartbeatSec` = 25 (default; raise for far/slow networks).
   - `showLog` = `true` while debugging.
4. Save. No station restart needed (`changed()` picks the change up).

The endpoint is then available at `https://<station-host>/niagaramcp/`.

---

## API endpoints

All endpoints require an `Authorization: Bearer <apiToken>` header.

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/niagaramcp/sse` | Server-Sent Events stream. The first event is `endpoint` with the URL for POST requests. Then heartbeats (`: ping`) and `event: message` with JSON-RPC responses. |
| `POST` | `/niagaramcp/messages?sessionId=<uuid>` | JSON-RPC 2.0 request. Accepted, the response is enqueued into the SSE stream. Returns `202 Accepted`. |

JSON-RPC methods:
- `initialize` → returns `protocolVersion`, `capabilities`, `serverInfo`.
- `notifications/initialized` → client signals it's ready.
- `ping` → `{}`.
- `tools/list` → list of 5 tools with `inputSchema`.
- `tools/call` → `{name: <toolName>, arguments: {...}}` → `{content: [{type:"text", text:"..."}], isError: <bool>}`.

### Tools

| name | purpose | params |
|---|---|---|
| `echo` | echo back `msg` | `msg: string` |
| `listChildren` | tree of node descendants | `ord: string`, `depth: 1..5` |
| `readPoint` | value and metadata of a `BControlPoint` | `ord: string` |
| `writePoint` | write to a writable point | `ord: string`, `value: any`, `priority: 1..16` (default 16) |
| `bqlQuery` | BQL → TSV (10s timeout) | `query: string` (full ord with `bql:`), `limit: 1..1000` (default 100) |

### Example call

```bash
TOKEN=<your-uuid>

# 1. Open SSE and read the endpoint
curl -N -H "Authorization: Bearer $TOKEN" \
     https://station/niagaramcp/sse
#  event: endpoint
#  data: /niagaramcp/messages?sessionId=abc-123

# 2. In another shell — initialize
curl -X POST -H "Authorization: Bearer $TOKEN" \
     "https://station/niagaramcp/messages?sessionId=abc-123" \
     -d '{"jsonrpc":"2.0","id":1,"method":"initialize"}'

# 3. tools/list
curl -X POST -H "Authorization: Bearer $TOKEN" \
     "https://station/niagaramcp/messages?sessionId=abc-123" \
     -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'
```

Connecting from an MCP client (Claude Desktop, Continue.dev, etc.) — standard SSE transport, URL `/niagaramcp/sse`, header `Authorization: Bearer <token>`.

---

## Security

### What you have

- Bearer-token auth on every request.
- Fail-secure: an empty `apiToken` → `401`.
- `UNAUTHENTICATED_ACCESS` permission declared explicitly with a `purposeKey` for audit.
- `service.enabled=false` → `503` on all endpoints.
- **Bounded SSE queue** (1000 messages per session). On overflow the message is dropped and a warning is logged via `bcLog` — no unbounded heap growth from a slow client.
- **10-second timeout** on `bqlQuery` cursor iteration. Truncated results are flagged in the response footer.

### What's missing (known limitations of v0.1.0)

| Gap | Mitigation today |
|---|---|
| Token stored as plain `String` property in `.bog` | Workbench access to the service exposes the token. Apply tight ACL on the service itself. |
| No per-ord RBAC — token = full write access | Use only in trusted environments; keep the token in the client's secret store. |
| No audit log for `writePoint` | To be added via `BAuditService` in a future version. |
| `CORS = *` in `WEB-INF/web.xml` | If on-prem — narrow to known origins. |
| No rate limiting | Don't expose to untrusted clients; keep behind a reverse proxy if reachable from the internet. |
| No automatic token rotation | Rotate manually. |

### Threat model in one line

> **Whoever has the token has root-engineer rights on the station.**

Use only behind TLS, generate the token as UUID v4, keep the station behind a network ACL, do not expose the endpoint to the internet without a reverse proxy with auth/rate-limit.

A detailed risk breakdown and proposed improvements live in [`_CODE_REVIEW.md`](./_CODE_REVIEW.md).

---

## Repo layout

```
niagaramcp/
├── README.md                        ← you are here (English)
├── README.ru.md                     ← Russian translation
├── LICENSE                          ← Apache 2.0
├── CHANGELOG.md
├── _CODE_REVIEW.md                  ← architecture + risk write-up
├── _SMOKE_TEST.md                   ← v0.1.0 smoke-test results
├── RELEASE.md                       ← git/release commands
├── .gitignore
└── niagaramcp-rt/                      ← Niagara runtime module
    ├── niagaramcp-rt.gradle.kts
    ├── module.palette
    ├── module.lexicon
    ├── module-permissions.xml
    ├── WEB-INF/web.xml
    └── src/
        ├── ru/bccontrol/json/         (embedded JSON parser)
        └── ru/bccontrol/niagaramcp/
            ├── BMcpPlatformService.java
            ├── McpProtocol.java
            ├── McpServlet.java
            ├── McpSession.java
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

- **v0.2**: `BPasswordPassword` for `apiToken`; `BAuditService` entries for `writePoint`/`bqlQuery`; full observability story (move `System.out.println` to `BLog`/`Logger`).
- **v0.3**: per-ord RBAC; separate read-only and write tokens.
- **v0.4**: shaded `com.niagaramcp.server.json` to remove classloader conflicts on MCS-style stations.
- **v0.5**: optional shaded jar with no embedded JSON; depend on `nre.jar` classpath.

---

## License

Apache License 2.0. See [`LICENSE`](./LICENSE).

Embedded `com.niagaramcp.json.*` — based on [stleary/JSON-java](https://github.com/stleary/JSON-java) (Public Domain).
