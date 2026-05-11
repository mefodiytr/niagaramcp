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
| `write` | `writePoint`, `createComponent` *(v0.5)*, `removeComponent`, `setSlot`, `invokeAction`, `addExtension`, `linkSlots`, `unlinkSlots`, `commitStation` *(v0.5.1)*, **`clearSlot`** *(v0.5.2)* — all but `writePoint` require user-Context |
| `walkthrough-read` | `getOverview`, `inspectComponent`, `findComponentsByType`, `getSlots` |
| `walkthrough-write` | 10 walkthrough writers |
| `management` | `getKnowledgeSummary`, `findUnmappedComponents`, `exportKnowledge`, `importKnowledge`, `reloadKnowledge` |
| `search` | `findEquipment`, `findInSpace`, `findPoints` |
| `history` | `readHistory` |
| `alarms` | `getActiveAlarms`, `getAlarmHistory` |
| `diagnostic` | `getServerInfo`, `probeOrd`, `checkKnowledgeIntegrity`, `getServiceHealth`, `getDiagnosticDump`, `getFeatureDump`, **`setupTestUser`** *(v0.5, gated by `enableTestSetup`)* |

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

## Write-tool polish (v0.5.2)

Small follow-ups to the M1 set — one new tool, one new arg form, a
pre-check, and a refactor. No new infrastructure.

- **`clearSlot`** (`write`, `requiresUserContext`, `MUTATION`) — resets a
  Property slot to its declared default (`Property.getDefaultValue()`)
  under the user-Context gateway. Works for any Property slot type, not
  just BSimple (no JSON value to coerce). Args `{ord, slotName}`; result
  `{ord, slotName, previousValue, defaultValue, type, changed}`.
- **`unlinkSlots` now also accepts `{sinkOrd, linkName}`** alongside
  `{linkOrd}` — mirrors how `linkSlots` returns its result and how a link
  reads in the nav tree (a named slot on the sink).
- **`addExtension` applicability pre-check** — runs Niagara's own
  `isChildLegal` / `isParentLegal` predicates; an incompatible
  parent/extension pair is now refused up front with **`-32015
  ERR_EXTENSION_NOT_APPLICABLE`** (`data{parentOrd, parentType,
  extensionType, reason}`) rather than a generic `-32603` at `add()` time.
  This activates the `-32015` code declared in v0.5.1.
- **`BValueCoercer`** — the JSON↔BSimple coercion shared by `setSlot` and
  `invokeAction` is now one helper (no behavior change). Also fixed
  `linkSlots`/`unlinkSlots` emitting relative `slot:/...` link ords
  (a `BLink` is a `BRelation`, not a `BComponent`).

`tools/list` 45 → **46**. Smoke `+1` step (28, `clearSlot`); steps 26-32.

---

## M1 write-tool tail (v0.5.1)

Stacked-on-v0.5 patch. Closes the M1 write-tool set with 6 more
tools + commitStation, all under the v0.5 user-Context gateway,
all audited. No new infrastructure — pure tool additions following
the `createComponent` reference shape.

### 7 new tools (all `category: "write"`, `requiresUserContext: true`)

| Tool | annotations | Purpose |
|---|---|---|
| `removeComponent` | `DESTRUCTIVE` | `parent.remove(slot, cx)`. Default `dryRun=true` + inbound-link safety check (refuse if `target.getLinks()` non-empty unless `force=true`). Outbound-link detection is queued for v0.6 (needs station walk). |
| `setSlot` | `MUTATION` | Type-coerced `BComplex.set(prop, value, cx)` for BSimple slot types (BString/BBoolean/BInteger/BLong/BFloat/BDouble). Complex types refuse with -32602 + hint. |
| `invokeAction` | `MUTATION` | `BComponent.invoke(Action, BValue, cx)` with parameter coercion mirroring setSlot. Returns `{returnValue, returnType, durationMs}`. |
| `addExtension` | `MUTATION` | `parent.add(name, ext, cx)` for extension types. v0.5.1 doesn't pre-check applicability; Niagara's add()-time validation surfaces incompatible pairs as -32603 (will become -32015 after v0.5.2 pre-check). |
| `linkSlots` | `MUTATION` | `sink.makeLink(...) + sink.add(linkName, link, cx)` after Niagara's `checkLink()` predicate. Type mismatch → -32016 with reason. Auto-pick converter (`convert: true`) deferred to v0.5.2. |
| `unlinkSlots` | `DESTRUCTIVE` | `sink.remove(linkProperty, cx)`. Refuses non-link ords (use removeComponent). Captures wire info (source/sink ord + slots) into result for audit / manual undo. |
| `commitStation` | `MUTATION` | `BStation.doSave(Context)` under user-Context. Use after a batch when ack-without-persistence (~30s auto-save delay) is unacceptable. |

### 4 new JSON-RPC error codes

| Code | Symbol | Trigger |
|---|---|---|
| `-32013` | `ERR_COMPONENT_HAS_INBOUND_LINKS` | `removeComponent` refused; `data{ord, inboundLinkCount, sampleSourceOrds[≤5]}`. |
| `-32014` | `ERR_ACTION_NOT_FOUND` | `invokeAction` action name not on the target. |
| `-32015` | `ERR_EXTENSION_NOT_APPLICABLE` | Reserved; activated when v0.5.2 adds pre-check. |
| `-32016` | `ERR_LINK_TYPE_MISMATCH` | `linkSlots` Niagara `LinkCheck` invalid; `data{sourceOrd, sourceSlot, sinkOrd, sinkSlot, reason}`. |

### Smoke

`+6` steps (26-31) under the existing v0.5 pre-flight (test BUser
+ `enableTestSetup=true`): own throwaway fixture via
createComponent → add a `baja:String` prop + setSlot it →
invokeAction error path (asserts `result.errorCode == -32014`) →
commitStation → removeComponent dryRun preview → removeComponent
actual cleanup. `--skip-v051` opts out. addExtension / linkSlots
/ unlinkSlots e2e fixtures need station-specific setup; deferred
to v0.5.2. **33 / 33 green against a live 4.15.3.28 station.**

### Post-smoke hardening

- **ord arguments** — write tools resolve ords against the running
  station (`BOrd.make(s).get(Sys.getStation())`), so a bare relative
  `slot:/Drivers/Foo` works as well as a fully-qualified
  `station:|slot:/Drivers/Foo`. Result ords are always returned
  fully-qualified.
- **`result.errorCode` / `result.errorData`** — a tool's `RpcException`
  (`-32013`..`-32016`, `-32006`, ...) is still reported MCP-style via
  `isError` content, but the structured code/data are now carried on the
  `CallToolResult` so clients can branch on them without parsing the
  message text. Generic exceptions keep the `Error: <msg>` text and no
  code.

---

## User-Context gateway & per-user audit (v0.5)

Foundation release for write-tools that mutate the station
component tree under the calling user's Niagara permissions
instead of the niagaramcp service identity. No breaking changes —
all 36 existing tools work exactly as before; the new pipeline
activates only when a tool sets `requiresUserContext=true` (only
`createComponent` in v0.5; the M1 set lands in v0.5.x).

### How user identity works

- Operator pre-creates a `BUser` via Workbench (UserService) and
  grants it the Niagara permissions the tool will need.
- A per-user MCP token is bound to the BUser via the
  `mcp:tokenHash` tag (salted SHA-256 against the per-service
  `tokenSalt` property, generated lazily on first start).
- On every request: `McpServlet` authenticates the bearer EITHER
  against the legacy `apiToken` (read-only service identity for
  monitoring) OR via `BearerResolver` walking
  `BUserService.getUsers()` with constant-time hash compare.
- Walks every user unconditionally — no early-exit on match — so
  total walk time doesn't leak which usernames are MCP-enrolled.
- Resolved BUser flows through `CallContext` to the tool body,
  which feeds it into `UserContextGateway.run(...)`. The gateway
  builds `BasicContext(user)`, runs the work lambda, wraps any
  `PermissionException` into `-32010` with rich
  `data{user, ord, operation, tool, detail}`, and emits one
  audit record.

### Per-user audit

- **JSONL primary**: every gateway call writes one JSON line to
  `<userHome>/niagaramcp/niagaramcp.audit.log`:
  `{ts, user, sessionId, tool, ord, action, args, resultOk,
  durationMs, errorCode, errorMessage}`. Args are passed through
  a redactor (default key blacklist:
  `password|secret|token|apikey|passcode|pwd|credential`) and
  long string values are truncated at 256 chars with a `…+N`
  suffix.
- **BAuditHistoryService secondary**: best-effort, **reflection-only**.
  Lookup at service start; method handle cached. No compile-time
  dependency on `history-rt` so lightweight JACE installs without
  that module load just fine. When the service is present, our
  records show in Workbench AuditView with a 6-field mapping
  (operation = action, target = ord, slotName = tool, value =
  "ok"|"FAIL: …", userName = user).

### MCP tool annotations

Per MCP 2025-06-18 §6.1, every `tools/list` row now carries
`annotations: {readOnlyHint, destructiveHint, idempotentHint,
openWorldHint}` plus a niagaramcp extension `requiresUserContext`.
MCP-aware clients (Claude Desktop, Cursor, MCP Inspector) gate
user-visible warnings on these — e.g. asking for explicit
confirmation before invoking `destructiveHint=true`. Existing 36
tools inherit `READ_ONLY` defaults — zero touch.

### `createComponent` tool (37th)

Reference write-tool for the M1 set. Adds a new `BComponent` of a
given Type as a child of an existing parent, under the calling
user's permissions.

```
{
  "parentOrd":    "station:|slot:/Drivers",
  "type":         "baja:Folder",
  "name":         "BasementHVAC",
  "nameStrategy": "fail" | "suffix"     // optional, default "fail"
}
```

Returns (in both `content[0].text` AND `structuredContent`):
`{ord, displayName, requestedName, resolvedName}`.

Errors: -32602 (missing arg / collision under "fail"), -32006
(parentOrd not resolvable), -32005 (type doesn't load), -32010
(permission denied), -32011 (bearer is service identity, not
BUser).

### Auto-promote `structuredContent`

McpProtocol now auto-promotes JSON-shaped tool results to MCP
`result.structuredContent` per spec §5.4. Pure addition: legacy
clients keep reading `content[0].text`, modern clients prefer the
typed structuredContent. Affects the entire 36-tool catalog at no
per-tool cost; no opt-in needed.

### `setupTestUser` tool (38th, test-only)

Gated by `BMcpPlatformService.enableTestSetup` (default `false`).
When enabled, lets the smoke client bind a freshly-generated bearer
to a pre-created BUser's `mcp:tokenHash` tag, so the v0.5 e2e smoke
step (createComponent under user-Bearer) runs without bog-fragment
provisioning. Production deployments leave the flag off.

### 2 new error codes

| Code | Symbol | When it fires |
|---|---|---|
| `-32010` | `ERR_PERMISSION_DENIED` | Niagara `PermissionException` from any mutating call inside a gateway-wrapped tool. `data{user, ord, operation, tool, detail}`. |
| `-32011` | `ERR_USER_NOT_FOUND` | Tool with `requiresUserContext=true` invoked with a bearer that resolves to apiToken (service identity) instead of a BUser. `data{tool, requiresUserContext: true}`. |

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
