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

## Streamable HTTP transport (v0.2.0)

Since v0.2.0 the module also speaks **Streamable HTTP** (MCP spec
2025-06-18) on the same servlet, alongside the legacy SSE+messages
transport. New clients that default to Streamable HTTP can connect
without any server-side configuration change.

| Method | Path | Purpose |
|---|---|---|
| `POST`   | `/niagaramcp/mcp` | JSON-RPC inbound. The first call must be `initialize`; the server returns a fresh `Mcp-Session-Id` response header. Subsequent calls echo it back. |
| `GET`    | `/niagaramcp/mcp` | Server-to-client SSE channel. Currently degenerate (immediate close) — no tool produces server-initiated push messages. |
| `DELETE` | `/niagaramcp/mcp` | Explicit session close. Idempotent. |

Session model:

- The **`Mcp-Session-Id`** request/response header carries a UUID.
- Sessions evict lazily after `mcpSessionIdleTimeoutSec` of inactivity
  (default **30 minutes**, configurable on the service).
- `DELETE /mcp` removes a session immediately.

### Curl example

```bash
TOKEN=<your-uuid>

# 1. initialize — server returns Mcp-Session-Id
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

The `/sse` + `/messages` transport remains fully supported; existing
v0.1.0 clients keep working unchanged.

---

## Semantic layer (v0.3.0)

Since v0.3.0 the module ships a **knowledge file** at
`${niagaraUserHome}/niagaramcp/knowledge.yaml` that holds the
semantic model of the station: spaces, equipment_types, equipment,
standalone points. AI clients use this to answer natural-language
questions about the station without slot-tree brute-force.

### How it works

1. Operator + AI run a **walkthrough** — AI inspects station
   structure, asks questions, writes confirmed mappings into
   knowledge.yaml.
2. After walkthrough, AI loads spine resources
   (`niagara://overview`, `niagara://kinds/catalog`) on connect and
   has the full picture in context.
3. New requests like *"what's the supply temp in parking sector E"*
   resolve in one round-trip via `findEquipment` + `readPoint`.

See `docs/concepts/01-concept.md` … `04-roadmap.md` for the design.

### v0.3.0 Tools added (23 of 28 total)

| Category | Tools |
|---|---|
| Walkthrough read | `getOverview`, `inspectComponent`, `findComponentsByType`, `getSlots` |
| Walkthrough write | `createSpace`, `updateSpace`, `createEquipmentType`, `updateEquipmentType`, `createEquipment`, `updateEquipment`, `bulkCreateEquipment`, `assignPointToEquipment`, `createStandalonePoint`, `validateKnowledge` |
| Knowledge mgmt | `getKnowledgeSummary`, `findUnmappedComponents`, `exportKnowledge`, `importKnowledge`, `reloadKnowledge` |
| Search | `findEquipment`, `findInSpace`, `findPoints` |
| History | `readHistory` (with optional client-side aggregation) |
| Alarms | `getActiveAlarms`, `getAlarmHistory` |

### v0.3.0 Resources

| URI | Notes |
|---|---|
| `niagara://overview` | Static; station identity + counts |
| `niagara://kinds/catalog` | Static; full equipment_types section |
| `niagara://equipment/{id}` | Template; full equipment record |
| `niagara://spaces/{id}` | Template; space + equipment + points within |
| `niagara://standalone-points/{id}` | Template; sensor record |
| `niagara://samples/standard-types` | Static; jar-bundled YAML with 5 generic types (opt-in via `importKnowledge` source='sample') |

### v0.3.0 Prompts

`walkthrough.new_station`, `walkthrough.continue`,
`walkthrough.verify_types`, `walkthrough.apply_pattern`,
`query.equipment_state`, `query.zone_comfort`,
`query.alarm_summary`.

See `_SMOKE_TEST.md` v0.3.0 section for curl-driven verification.

---

## Diagnostics & samples (v0.3.1)

v0.3.1 is a patch release adding diagnostic capabilities and a
sample-data folder.

### Diagnostic tools (4 new, 32 total)

| Tool | Purpose |
|---|---|
| `getServerInfo` | Snapshot: version, uptime, sessions, knowledge file, transports, registered tools/resources/prompts |
| `probeOrd {ord}` | Resolve an ord; returns exists/type/displayName/parentOrd/slotCount + isControlPoint/isWritable/isAlarmSource flags + hasHistoryExt + historyExtCount + historyExtIds. Garbage ords yield `{exists:false}`, never an error. |
| `checkKnowledgeIntegrity` | Iterate every ord in the knowledge model, report broken refs |
| `getServiceHealth` | Niagara service availability + knowledge file readability/writability + sample resource read-back |

### Unauthenticated `/health` endpoint

```
GET /niagaramcp/health
```

The ONE exception to Bearer-on-every-endpoint — designed for
external monitoring (k8s/Prometheus/watchdog probes). Returns:

- `200 OK` + JSON `{status:"ok", version, uptimeSeconds, knowledgeFileSize, sessionCount, healthyServices}` in normal operation.
- `503 Service Unavailable` + same shape with `status:"degraded"` when alarm/history service unavailable, knowledge file unreadable, or platform service disabled.

Exposes only counts and per-service ok/missing booleans — no
station data, equipment names, or ords.

### New configuration properties

- `mcpProtocolVersion` (String) — override the MCP protocol version advertised in `initialize`. Default: empty → `2025-06-18`.
- `maxHistoryRecordsPerQuery` (int) — cap for `readHistory`. Default: 10000.
- `disabledTools` (String, comma-separated) — operator can suppress specific tools at startup. Restart-required.

### Standardised JSON-RPC error codes

8 niagaramcp-defined codes in the JSON-RPC implementation-defined band: `-32001..-32008` covering session/tool/resource/knowledge/schema/ord/history/alarm errors. Error responses now include an `error.data` JSONObject with diagnostic context (`{toolName: "..."}`, `{uri: "..."}`, etc.).

### Samples folder

`samples/mall-knowledge.yaml` (synthetic shopping-mall fixture) +
`samples/README.md` ship with the repo. NOT bundled into the jar —
operators import via `importKnowledge` to test queries without a
real walkthrough. The jar-bundled `niagara://samples/standard-types`
(5 generic equipment types) from v0.3.0 remains separate.

---

## Operational features (v0.4.0)

v0.4.0 is a minor release adding operational controls and reducing
jar size from ~243 KB to ~213 KB.

### Transport toggles

Two new properties on `BMcpPlatformService`:

- `sseEnabled` (default `true`) — controls `/sse` + `/messages`.
- `streamableEnabled` (default `true`) — controls `POST/GET/DELETE /mcp`.

Disabling a transport returns HTTP 503 + JSON-RPC body
`{error:{code:-32009, message:"Transport disabled: …", data:{transport:…}}}`.
`/health` is exempt — monitors stay accessible regardless.
Restart-required (v0.5 may add runtime apply).

### Tool category tags

`tools/list` response now includes a `category` field per tool, so
clients can group tools in their UI:

| Category | Tools |
|---|---|
| `transport-test` | `echo` |
| `read` | `listChildren`, `readPoint`, `bqlQuery` |
| `write` | `writePoint` |
| `walkthrough-read` | `getOverview`, `inspectComponent`, `findComponentsByType`, `getSlots` |
| `walkthrough-write` | 10 walkthrough writers |
| `management` | `getKnowledgeSummary`, `findUnmappedComponents`, `exportKnowledge`, `importKnowledge`, `reloadKnowledge` |
| `search` | `findEquipment`, `findInSpace`, `findPoints` |
| `history` | `readHistory` |
| `alarms` | `getActiveAlarms`, `getAlarmHistory` |
| `diagnostic` | `getServerInfo`, `probeOrd`, `checkKnowledgeIntegrity`, `getServiceHealth`, `getDiagnosticDump`, **`getFeatureDump`** |

### `getDiagnosticDump` tool (35th)

One-shot snapshot for ops dashboards — combines server identity,
sessions, knowledge stats, service health, and the last 20 lines of
the audit log into one JSON response.

### `serverInfo.transports`

`initialize` response now advertises which transports are currently
enabled in `serverInfo.transports`. Clients still pick by URL —
informational only.

### Jar size reduction

12 unused JSON utility classes (`XML`, `CDL`, `Cookie`, `HTTP`, etc.)
removed from the embedded `com.niagaramcp.json` package. Jar size
243 KB → 213 KB (−30 KB / −12%).

---

## Workbench polish & feature dump (v0.4.1)

UX-polish patch release. No breaking changes.

### Read-only count properties on the Property Sheet

`BMcpPlatformService` exposes 4 SUMMARY+READONLY counters so an
operator opening the service in Workbench sees runtime stats at a
glance — no need to call a diagnostic tool:

| Property | Source |
|---|---|
| `toolCount` | `ToolRegistry.all().size()` (36 in v0.4.1) |
| `resourceCount` | `ResourceRegistry.all().size()` (6) |
| `promptCount` | `PromptRegistry.all().size()` (7) |
| `sessionCount` | `McpSessions.activeCount()` (combined SSE + Streamable) |

`sessionCount` updates immediately on session create/remove via a
static notification — no polling, no new threads. Per-transport
split is deferred to v0.5 along with a typed-iterator extension to
the `McpSessions` API.

### `getFeatureDump` tool (36th)

Static feature inventory of the running server, designed for AI
clients on discovery and operators copy-pasting MCP responses. Two
formats:

- `text` (default) — human-readable banner with tools grouped by
  category, resources, prompts, transport flags, knowledge stats,
  sessions, health, and the 9 impl-defined JSON-RPC error codes
  (-32001..-32009) with their meanings.
- `json` — same data structured for programmatic consumption.

Counterpart to v0.4 `getDiagnosticDump` (dynamic state) — this one
is purely the static catalog.

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

- **v0.2**: `BPasswordPassword` for `apiToken`; `BAuditService` entries for `writePoint`/`bqlQuery`; full observability story (move `System.out.println` to `BLog`/`Logger`).
- **v0.3**: per-ord RBAC; separate read-only and write tokens.
- **v0.4**: shaded `com.niagaramcp.server.json` to remove classloader conflicts on MCS-style stations.
- **v0.5**: optional shaded jar with no embedded JSON; depend on `nre.jar` classpath.

---

## License

Apache License 2.0. See [`LICENSE`](./LICENSE).

Embedded `com.niagaramcp.json.*` — based on [stleary/JSON-java](https://github.com/stleary/JSON-java) (Public Domain).
