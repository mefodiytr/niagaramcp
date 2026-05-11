# niagaramcp — API reference

The HTTP / JSON-RPC surface exposed by the **niagaramcp** Niagara module
(`BMcpPlatformService`). Implements the Model Context Protocol (MCP), spec
revision **2025-06-18**, over two transports on a single servlet.

> The authoritative, machine-readable inventory of what a *running* instance
> exposes is the `getFeatureDump` tool (`{"format":"json"}`) and the standard
> `tools/list` / `resources/list` / `prompts/list` calls. This document is
> the human reference; if it disagrees with a live instance, the live
> instance wins.

- Tools: **46** across 10 categories
- Resources: **6** (3 fixed-URI, 3 templated)
- Prompts: **7**
- Error codes: standard JSON-RPC + impl-defined `-32001 … -32016`

Contents: [Endpoints](#endpoints) · [Authentication](#authentication) ·
[User-Context write model](#user-context-write-model) ·
[JSON-RPC methods](#json-rpc-methods) · [Tool call result shape](#tool-call-result-shape) ·
[Error codes](#error-codes) · [Tool catalog](#tool-catalog) ·
[Resources](#resources) · [Prompts](#prompts) ·
[Service configuration](#service-configuration) · [Versioning](#versioning)

---

## Endpoints

Base path: `/<modulePath>/` (default `/niagaramcp/`) on the Niagara web server
(`https://<station-host>/niagaramcp/`).

| Method | Path | Auth | Purpose |
|---|---|---|---|
| `POST`   | `/niagaramcp/mcp` | Bearer | **Streamable HTTP** — JSON-RPC inbound. First request must be `initialize`; the response carries a fresh `Mcp-Session-Id` header; subsequent requests echo it back. |
| `GET`    | `/niagaramcp/mcp` | Bearer | Streamable HTTP server→client SSE channel. Currently degenerate (closes immediately) — no tool produces push messages yet. |
| `DELETE` | `/niagaramcp/mcp` | Bearer | Explicitly close a Streamable HTTP session. Idempotent → `204`. |
| `GET`    | `/niagaramcp/sse` | Bearer | **Legacy SSE transport** — emits an `endpoint` event with the per-session POST URL, then heartbeats (`: ping`) and `event: message` JSON-RPC responses. |
| `POST`   | `/niagaramcp/messages?sessionId=<uuid>` | Bearer | Legacy transport — JSON-RPC request; enqueued onto the SSE stream → `202 Accepted`. |
| `GET`    | `/niagaramcp/health` | **none** | Liveness/version JSON: `{status, version, healthyServices:[...], ...}`. Safe to expose to a monitor. |

Both transports run side-by-side; new MCP clients that default to Streamable
HTTP need no server-side change. `serverInfo.transports` reports
`["sse","streamable-http"]`.

### Session model

- Streamable HTTP: `Mcp-Session-Id` request/response header carries a UUID.
  Sessions are evicted lazily after `mcpSessionIdleTimeoutSec` of inactivity
  (default **30 minutes**); `DELETE /mcp` removes one immediately. A request
  with a stale/unknown id → `404`. A request without `initialize` first → `400`.
- Legacy SSE: the `sessionId` lives in the SSE stream + the `?sessionId=`
  query param.

---

## Authentication

Every endpoint except `/health` requires `Authorization: Bearer <token>`.
Missing/invalid → `401` with `WWW-Authenticate: Bearer`.

Two kinds of bearer token are accepted:

1. **`apiToken`** — the value of the `apiToken` property on
   `BMcpPlatformService`. Authenticates as the **niagaramcp service
   identity**: a non-user principal. Read tools, search, history, alarms,
   diagnostics and knowledge tools all work. **Write tools that mutate the
   station component tree refuse it with `-32011`** — they need a real
   Niagara user (see below). Treat this token like an admin password.

2. **A per-user MCP token** — a token bound to a Niagara `BUser` via that
   user's `mcp:tokenHash` tag (salted SHA-256 of the token, constant-time
   compared; the salt is the read-only `tokenSalt` property on
   `BMcpPlatformService`). Requests carrying such a token run **as that
   `BUser`** — mutating Baja calls are made with a `Context` impersonating
   the user, so Niagara's own permission model applies, and every mutation
   is audited under the user's name. There is no MCP-side endpoint to mint
   these tokens; an operator binds the hash (Workbench, or — on a CI/smoke
   station — the gated `setupTestUser` tool).

A given request is one or the other: if the bearer equals `apiToken` it is
the service identity (never a user), otherwise it is resolved against the
`mcp:tokenHash` walk.

---

## User-Context write model

Tools whose `tools/list` entry has `"requiresUserContext": true` (all
`category:"write"` tools except `writePoint`, plus a few others) only run
under a user-bearer. The pipeline:

- The dispatch layer resolves the bearer to a `BUser`; if the tool needs one
  and there isn't one → **`-32011` ERR_USER_NOT_FOUND** before the tool body
  runs.
- The mutating part of the tool runs inside `UserContextGateway.run(...)`,
  which builds a `BasicContext(user)` and passes it to the Baja call
  (`add`/`set`/`remove`/`invoke`/`doSave`). If Niagara raises a
  `PermissionException` → **`-32010` ERR_PERMISSION_DENIED** with
  `data{user, ord, operation, tool, detail}`.
- One audit record per call (start time, user, sessionId, tool, ord, action,
  redacted args, ok/fail, durationMs, errorCode) → a JSON-lines file under
  `<userHome>/niagaramcp/niagaramcp.audit.log` and (if the history module is
  present) the Workbench audit history via `javax.baja.security.Auditor`.
  Secrets in args are redacted.

Writes are acknowledged without forcing a `.bog` save (Niagara's ~30 s
auto-save still applies); call **`commitStation`** to flush synchronously.

---

## JSON-RPC methods

JSON-RPC 2.0. Methods (on both transports):

| Method | Purpose |
|---|---|
| `initialize` | Returns `{protocolVersion:"2025-06-18", capabilities, serverInfo}`. On Streamable HTTP, allocates the session and sets `Mcp-Session-Id`. |
| `notifications/initialized` | Client signals readiness. No response. |
| `ping` | `{}`. |
| `tools/list` | `{tools:[{name, description, inputSchema, annotations:{readOnlyHint,destructiveHint,idempotentHint,openWorldHint}, requiresUserContext, category}, ...]}`. |
| `tools/call` | `{name, arguments:{...}}` → see [result shape](#tool-call-result-shape). |
| `resources/list` | `{resources:[{uri|uriTemplate, name, mimeType, description}, ...]}`. |
| `resources/read` | `{uri}` → `{contents:[{uri, mimeType, text}]}`. |
| `prompts/list` | `{prompts:[{name, description, arguments:[...]}, ...]}`. |
| `prompts/get` | `{name, arguments:{...}}` → `{messages:[{role, content}, ...]}`. |

`serverInfo` (from `initialize`) includes `name`, `version`, `transports`.

---

## Tool call result shape

`tools/call` returns a JSON-RPC `result` shaped like MCP `CallToolResult`:

```json
{
  "content": [ { "type": "text", "text": "..." } ],
  "isError": false,
  "structuredContent": { ... }
}
```

- **`content[0].text`** — always present. For tools that produce JSON it is
  that JSON string; for plain-text tools it's the text; for errors it's the
  error message (`"Error: ..."` for an unexpected exception, the bare message
  for a structured one).
- **`structuredContent`** — present when `content[0].text` parses as a JSON
  object (auto-promoted per MCP §5.4). Prefer this over re-parsing the text.
  Not added when `isError` is true.
- **`isError`** — `true` if the tool raised. (Note: tool failures are
  reported MCP-style via `isError`, **not** as a JSON-RPC `error` object.)
- **`errorCode` / `errorData`** *(niagaramcp extension)* — when `isError` and
  the failure was a structured error, these carry the impl-defined code (int)
  and its `data` object, so a client can branch on `-32013…-32016` etc.
  without parsing the text. Generic exceptions carry neither.

Errors raised **before** a tool body runs (unknown method, unknown tool,
schema-invalid args, session not found, `requiresUserContext` unmet, …) come
back as ordinary JSON-RPC `error` objects with `code`/`message`/`data`.

---

## Error codes

Standard JSON-RPC: `-32700` parse error, `-32600` invalid request,
`-32601` method not found, `-32602` invalid params, `-32603` internal error.

Impl-defined (niagaramcp):

| Code | Symbol | Meaning |
|---|---|---|
| `-32001` | ERR_SESSION_NOT_FOUND | Mcp-Session-Id unknown/expired. |
| `-32002` | ERR_TOOL_NOT_FOUND | `tools/call` for an unregistered tool. |
| `-32003` | ERR_RESOURCE_NOT_FOUND | `resources/read` for an unknown URI. |
| `-32004` | ERR_KNOWLEDGE_UNREADABLE | Knowledge file can't be read. |
| `-32005` | ERR_SCHEMA_VALIDATION | A `module:Type` spec doesn't load / is abstract; schema mismatch. |
| `-32006` | ERR_ORD_NOT_RESOLVABLE | An ord arg doesn't resolve, or resolves to the wrong kind of object. |
| `-32007` | ERR_HISTORY_EXT_MISSING | A history operation needs a history extension that isn't present. |
| `-32008` | ERR_ALARM_SERVICE_MISSING | An alarm tool but no `BAlarmService`. |
| `-32009` | ERR_TRANSPORT_DISABLED | A transport-specific call but that transport is off. |
| `-32010` | ERR_PERMISSION_DENIED | The acting `BUser` lacks the Niagara permission for the operation. `data{user, ord, operation, tool, detail}`. |
| `-32011` | ERR_USER_NOT_FOUND | The tool `requiresUserContext` but the bearer is the service identity (or didn't resolve to a `BUser`). |
| `-32013` | ERR_COMPONENT_HAS_INBOUND_LINKS | `removeComponent` refused: target has inbound links and `force=false`. `data{ord, inboundLinkCount, sampleSourceOrds[≤5]}`. |
| `-32014` | ERR_ACTION_NOT_FOUND | `invokeAction`: no such action on the target. |
| `-32015` | ERR_EXTENSION_NOT_APPLICABLE | `addExtension`: `isChildLegal`/`isParentLegal` pre-check failed. `data{parentOrd, parentType, extensionType, reason}`. |
| `-32016` | ERR_LINK_TYPE_MISMATCH | `linkSlots`: Niagara's `checkLink` is invalid and no `converterType` was given. `data{sourceOrd, sourceSlot, sinkOrd, sinkSlot, reason}`. |

Tool-domain codes (`-32006`, `-32013…-32016`, `-32010`, …) raised from
*inside* a tool surface via `result.isError` + `result.errorCode`; the rest
surface as JSON-RPC `error` objects (see above).

---

## Tool catalog

`tools/list` returns the full input schema for each. Below: name, category,
whether it requires a user-Context bearer, annotations, and a one-line
summary. (Args shown where short; otherwise see `inputSchema`.)

### `transport-test`

| Tool | uC | Summary |
|---|:-:|---|
| `echo` | – | Return the given `msg` back (connectivity check). |

### `read`

| Tool | uC | Summary |
|---|:-:|---|
| `listChildren` | – | Children of an ord to a given `depth` (1–5): `{name, ord, type, displayName, isPoint, children?}`. Station overview without BQL. |
| `readPoint` | – | Current value + metadata of a control point by ord: `{ord, displayName, type, value, status, priority, out, facets}`. |
| `bqlQuery` | – | Run a BQL query (full ord with `bql:`), return tabular results. `limit` 1–1000 (default 100), 10 s timeout. |

### `write`

All require a user-Context bearer **except `writePoint`**.

| Tool | uC | Annotations | Summary |
|---|:-:|---|---|
| `writePoint` | – | mutation | Write to a writable point (Numeric/Boolean/String/Enum) at `priority` 1–16 (default 16). `value:null` releases that priority level. |
| `createComponent` | ✓ | mutation | Add a new `BComponent` (`type` = `module:TypeName`) as a child of `parentOrd`. `nameStrategy` = `fail` (default) \| `suffix`. Returns the new ord. |
| `setSlot` | ✓ | mutation | Set a slot to a JSON value, coerced to the slot's existing BSimple type (String/Boolean/Integer/Long/Float/Double). Args `{ord, slotName, value}`. |
| `clearSlot` | ✓ | mutation | Reset a Property slot to its declared default. Args `{ord, slotName}`. Works for any Property type. |
| `invokeAction` | ✓ | mutation | Invoke an Action; `args` coerced to its BSimple parameter type (or use the default). Returns `{returnValue, returnType, durationMs}`. |
| `addExtension` | ✓ | mutation | Add an extension instance (history/alarm/proxy ext, …) as a child. Pre-checks applicability (`isChildLegal`/`isParentLegal`) → `-32015` if not applicable. |
| `linkSlots` | ✓ | mutation | Wire `sourceOrd.sourceSlot` → `sinkOrd.sinkSlot` via a `BLink` stored on the sink. `checkLink` first; mismatch → `-32016`. Optional `converterType` (`module:Type` of a `BConverter`) → adds a `BConversionLink` and bypasses the mismatch refusal. Optional `linkName`. |
| `unlinkSlots` | ✓ | destructive | Remove a `BLink` slot. Args `{linkOrd}` **or** `{sinkOrd, linkName}`. Refuses non-link slots. Captures the wire info into the result. |
| `removeComponent` | ✓ | destructive | Remove a component from its parent. `dryRun=true` by default; refuses if inbound links exist unless `force=true`. Does **not** detect outbound links. |
| `commitStation` | ✓ | mutation | Force a synchronous `.bog` save under the user's permissions. No args. |

### `walkthrough-read`

(Read of the semantic "knowledge" model layered over the station.)

| Tool | uC | Summary |
|---|:-:|---|
| `getOverview` | – | Top-level station structure: name, top-level slots, typed-child counts. |
| `inspectComponent` | – | Single component by ord: `{ord, name, displayName, type, parentOrd, childCount}`. |
| `getSlots` | – | All properties of a component: each slot's `name, type, value (toString), facets (units, precision)`. |
| `findComponentsByType` | – | All components matching a Niagara type (`BNumericPoint` or `control:NumericPoint`). |

### `walkthrough-write`

(Edit the knowledge model — these write to the knowledge YAML/JSON, not the
station tree, so they don't take a user-Context.)

| Tool | uC | Summary |
|---|:-:|---|
| `createSpace` | – | New space (zone/floor/parking/…). `id` unique, kebab-case. |
| `updateSpace` | – | Update a space's fields (only those present in args); `id` for lookup. |
| `createEquipmentType` | – | New `equipment_type` (AHU/Chiller/Pump template). `extends` references another type id (cycle-checked). |
| `updateEquipmentType` | – | Update a type's fields; `typical_points` (if given) **replaces** the list. |
| `createEquipment` | – | New equipment instance; `type` references an existing `equipment_type`; `ord` is its root component's ord. |
| `bulkCreateEquipment` | – | Create many equipment entries atomically (all validate, then all-or-none). |
| `updateEquipment` | – | Update an equipment entry; `points` (if given) **merges** into the role→ord map. |
| `assignPointToEquipment` | – | Assign an ord to a semantic role (e.g. `supply_air_temp`) on an equipment. Role free-form; ord syntax-checked only. |
| `createStandalonePoint` | – | Register a stand-alone point (ambient temp/CO₂ sensor …) under a space. |
| `validateKnowledge` | – | Validate the knowledge model against schema rules; returns warnings (orphan refs, dup ids, parent cycles). Read-only. |

### `management`

| Tool | uC | Summary |
|---|:-:|---|
| `getKnowledgeSummary` | – | Counts of spaces / equipment types / equipment / standalone points; schema version; storage format. |
| `findUnmappedComponents` | – | Components matching `typeName` not referenced by any knowledge `ord` — finds points still needing walkthrough coverage. |
| `exportKnowledge` | – | Whole knowledge model as a string; `format` = `yaml` (default) \| `json`. |
| `importKnowledge` | – | Import a knowledge doc (`content` raw YAML/JSON, or `source='sample'`); `mode` = `merge` (skip id collisions) \| `replace`. |
| `reloadKnowledge` | – | Re-read the knowledge file from disk (after an external edit). No-op if already current. |

### `search`

(Search the knowledge model.)

| Tool | uC | Summary |
|---|:-:|---|
| `findEquipment` | – | Equipment matching a query (name + aliases + id, case-insensitive substring); ranked. |
| `findInSpace` | – | Equipment + standalone points in a space (`recursive` includes descendants; optional `equipmentType` filter). |
| `findPoints` | – | Points by `role` / `kind` (temperature/boolean/…) / `text` (≥1 required). |

### `history`

| Tool | uC | Summary |
|---|:-:|---|
| `readHistory` | – | History records for a point or `BHistoryExt` between `from`/`to` (ISO datetime or epoch ms). Optional `aggregation` = none\|avg\|min\|max\|count (client-side). `limit` default 1000, max 10000; 10 s iteration timeout. |

### `alarms`

| Tool | uC | Summary |
|---|:-:|---|
| `getActiveAlarms` | – | Currently-open alarms (off-normal/fault, not yet normal). Optional `sourceOrdPrefix` filter. |
| `getAlarmHistory` | – | Historical alarm records (`from`/`to`); optional `sourceOrdPrefix`. Capped at 2000 rows / 10 s; truncation flagged in footer. |

### `diagnostic`

| Tool | uC | Summary |
|---|:-:|---|
| `getServerInfo` | – | `version, uptimeSeconds, sessionCount, knowledgeFile{...}, transports, ...`. |
| `getServiceHealth` | – | `{alarmService, historyService, knowledgeFile{readable,writable,exists}, knowledgeAuditLog{writable}, sampleResource}`. |
| `getDiagnosticDump` | – | One-shot snapshot: server identity + active sessions + knowledge stats + service health + last 20 lines of the knowledge audit log. |
| `getFeatureDump` | – | Static feature inventory (version, tools by category, resources, prompts, transports, knowledge stats, session count, health, error codes). `format` = `text` (default) \| `json`. |
| `probeOrd` | – | Probe an ord: `{exists, type, displayName, parentOrd, slotCount, isControlPoint, isWritable, hasHistoryExt, historyExtCount, historyExtIds, isAlarmSource}`. Unresolvable → `{exists:false}` (no error). |
| `checkKnowledgeIntegrity` | – | Check every ord in the knowledge model resolves on the station: `{totalRefs, validRefs, brokenRefs:[{equipment|point, role?, ord, reason}]}`. |
| `setupTestUser` | – | **TEST-ONLY**, gated by `BMcpPlatformService.enableTestSetup` (default false). Binds an `mcp:tokenHash` tag to a pre-created `BUser` so a smoke client can authenticate as that user. Args `{username, token}`. Refuses unless `enableTestSetup=true`. |

`uC` = requires a user-Context bearer.

---

## Resources

`resources/list` → `resources/read {"uri": ...}`.

| URI / template | Kind | Content |
|---|---|---|
| `niagara://overview` | fixed | Short orientation text about the station / this server. |
| `niagara://kinds/catalog` | fixed | Catalog of the point "kinds" the knowledge model recognises. |
| `niagara://samples/standard-types` | fixed | Sample standard Niagara types reference. |
| `niagara://equipment/{id}` | templated | A knowledge equipment entry by id. |
| `niagara://space/{id}` | templated | A knowledge space entry by id. |
| `niagara://standalone-point/{id}` | templated | A knowledge stand-alone point entry by id. |

(Templated resources resolve `{id}` against the knowledge model; an unknown
id → `-32003`.)

---

## Prompts

`prompts/list` → `prompts/get {"name": ..., "arguments": {...}}` →
`{messages:[{role, content}, ...]}`.

| Name | Purpose |
|---|---|
| `walkthrough.new_station` | Kick off mapping a fresh station: read overview, list children, build the knowledge model. |
| `walkthrough.continue` | Resume an in-progress walkthrough — find unmapped components and keep going. |
| `walkthrough.verify_types` | Verify the equipment-type templates against what's actually on the station. |
| `walkthrough.apply_pattern` | Apply a recognised equipment pattern to similar components. |
| `query.equipment_state` | Summarise an equipment's current point values / status. |
| `query.zone_comfort` | Comfort assessment for a zone (temp/CO₂/setpoints). |
| `query.alarm_summary` | Summarise current alarms. |

---

## Service configuration

`BMcpPlatformService` (drop into `Services/`):

| Property | Type | Default | Notes |
|---|---|---|---|
| `enabled` | boolean | true | Master on/off. `changed()` picks up edits — no station restart. |
| `apiToken` | String | (empty) | The service-identity bearer. Set to a high-entropy random value. **Admin-equivalent.** |
| `tokenSalt` | String (readonly) | auto | Base64, 16 bytes, lazily generated on first start. Salt for `mcp:tokenHash`. |
| `enableTestSetup` | boolean | false | Gates the `setupTestUser` tool. Flip to true only on a smoke/CI station; flip back after. |
| `sseHeartbeatSec` | int | 25 | SSE keep-alive interval. |
| `mcpSessionIdleTimeoutSec` | int | 1800 | Streamable HTTP session idle eviction. |
| `showLog` | boolean | false | Verbose logging while debugging. |
| (transport enables) | boolean | true | Per-transport on/off (SSE / Streamable HTTP). |

Per-user MCP tokens: bind `mcp:tokenHash` = `lower-hex(SHA-256(salt_bytes ‖
token_utf8))` (salt = `tokenSalt`, base64-decoded) as a tag on the `BUser`.

---

## Versioning

- Module version: see `getServerInfo`.`version` / `getFeatureDump`.`version`.
- Protocol: MCP **2025-06-18**.
- Release history & per-version detail: [`CHANGELOG.md`](../CHANGELOG.md),
  [`docs/SESSIONS.md`](./SESSIONS.md), `docs/v0.5*-implementation-notes.md`.
- Backward compatibility: tool/resource/prompt additions are non-breaking;
  the legacy SSE+messages transport is retained alongside Streamable HTTP.
