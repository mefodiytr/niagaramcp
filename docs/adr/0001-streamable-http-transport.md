# ADR-0001: Streamable HTTP transport

## Status

**PROPOSED** — 2026-05-09

## Context

niagaramcp v0.1.0 ships a single MCP transport: SSE long-poll on
`GET /niagaramcp/sse` plus `POST /niagaramcp/messages?sessionId=…` for
inbound requests. The pattern is implemented end-to-end in
`McpServlet.handleSse()` (`niagaramcp-rt/src/com/niagaramcp/server/McpServlet.java:64-111`)
and `McpServlet.handleMessage()` (same file, `:113-141`), with the
session abstraction in `McpSession.java:11-67`. Per recon
`docs/recon-2026-05-09.md` §6, this transport is correct and
operational, but the MCP spec has since defined and standardised
Streamable HTTP as the preferred transport for new clients (spec
revisions 2025-03-26 and 2025-06-18). MCP clients increasingly default
to Streamable HTTP and a number of n8n-class integrations no longer
ship SSE-capable transports out of the box. Without Streamable HTTP
support, niagaramcp risks becoming the legacy option in its own
ecosystem.

The recon (`docs/recon-2026-05-09.md` §13) ranked the work for adding
the new transport: **0 blockers, 3 refactor-needed items, 7
clean-addition items**. The protocol layer (`McpProtocol`, file
`niagaramcp-rt/src/com/niagaramcp/server/McpProtocol.java`) was
verified to be transport-agnostic — `handle(JSONObject, ToolRegistry,
Session)` only touches the session for `markInitialized()` (per recon
§5.2 and §13.1). Auth sits above the transport
(`McpServlet.java:151-168`, recon §13.4). Tools are
protocol-agnostic (recon §8.7). The only place SSE assumptions leak in
is the **session model** — currently 1 SSE GET = 1 session, lifetime
bound to the connection (`McpSession.java`, `McpSessions.java`, recon
§6.5 and §13.2).

This ADR formalises the design before code changes. It scopes the
refactor to the smallest possible reasonable change set and commits
to backward compatibility with v0.1.0 SSE deployments.

## Decision

### 1. Add Streamable HTTP **alongside** SSE on the same servlet

`McpServlet` keeps its current SSE behaviour at `/sse` and
`/messages`, and grows three new branches: `POST /mcp`, `GET /mcp`,
`DELETE /mcp`. The branching extends the existing path-matching
in `doGet`/`doPost` (recon §4.3 file:line `McpServlet.java:41-62`)
and adds a new `doDelete` override. Auth and the
`checkServiceEnabled` gate are inherited automatically because they
sit above path matching (recon §13.4). A separate
`McpStreamableServlet` class was rejected (see Alternatives) — the
single-servlet design absorbs two endpoints comfortably and avoids
duplicating the auth/enabled checks. Per recon §13.6 backward
compat is naturally achieved: existing SSE clients keep using
`/sse` + `/messages?sessionId=…` without code changes; new clients
use `/mcp` with the `Mcp-Session-Id` header.

### 2. Extract a `Session` interface; specialise per transport

`com.niagaramcp.server.Session` becomes the transport-agnostic
abstraction. The current `McpSession` (file
`niagaramcp-rt/src/com/niagaramcp/server/McpSession.java:28-83`)
is renamed `SseSession` and gains `implements Session`; its
behaviour (`LinkedBlockingQueue<>(MAX_QUEUE)`, `enqueue` with
overflow log, `__CLOSE__` sentinel, `poll(timeoutMs)`) is preserved
verbatim. A new `StreamableSession implements Session` carries
just an id, `lastSeenMs`, and `closed`/`initialized` flags — no
queue, because Streamable HTTP responses are written directly to
the originating POST's `HttpServletResponse`. `McpSessions` (file
`niagaramcp-rt/src/com/niagaramcp/server/McpSessions.java:26-58`)
keeps its static `ConcurrentHashMap` registry but typed as
`Map<String, Session>`. `McpProtocol.handle(...)` (file
`niagaramcp-rt/src/com/niagaramcp/server/McpProtocol.java:39`)
takes `Session` instead of `McpSession`; the only method it calls
on the session, `markInitialized()`, is on the interface.

### 3. Session lifecycle uses **lazy idle eviction** — no scheduler threads

`StreamableSession` carries a `volatile long lastSeenMs`. Every
acquire from the registry checks
`(now - lastSeenMs) > IDLE_TIMEOUT_MS`; stale sessions are removed
and `close()`d at the point of acquisition, before the request
handler proceeds. There is no background sweeper thread. This
preserves the recon §10.4 baseline of **zero
`ExecutorService`/`ScheduledExecutorService`/`Thread.start()`** in
the codebase. Lazy eviction is sufficient at current scale (small
number of MCP clients per station, sessions used or abandoned in
minutes); a background sweeper can be added later if memory
pressure becomes measurable.

### 4. Default `IDLE_TIMEOUT_MS` = 30 minutes; configurable per service

A new `BMcpPlatformService` property `mcpSessionIdleTimeoutSec`
(default `1800`) controls the idle window. Wired with the same
`@NiagaraProperty` annotation used for the existing
`sseHeartbeatSec` slot
(`niagaramcp-rt/src/com/niagaramcp/server/BMcpPlatformService.java:40-46,69-71`).
A static accessor `mcpSessionIdleTimeoutMs()` returns the value
multiplied by 1000, mirroring the `sseHeartbeatSec()` accessor at
`BMcpPlatformService.java:156-159`. 30 minutes matches typical
human inactivity windows in chat-style MCP clients without being so
short that users notice eviction during normal use.

### 5. Bump MCP `PROTOCOL_VERSION` to `"2025-06-18"`

The constant at
`niagaramcp-rt/src/com/niagaramcp/server/McpProtocol.java:30`
moves from `"2024-11-05"` to `"2025-06-18"`. The capability shape
returned by `buildInitializeResult()` (`McpProtocol.java:78-91`)
is inspected and adjusted only if the spec changed it for the
methods we implement (`tools/list`, `tools/call`, `ping`,
`notifications/initialized`). Older clients negotiating
`2024-11-05` see a different version string but the protocol
remains compatible at the JSON-RPC level for the methods in scope.

## Consequences

**Positive**

- **Backward-compatible v0.1.0 path is preserved**. `/sse` +
  `/messages` continues to work bit-for-bit; existing deployments
  upgrade transparently.
- **No new threads**: lazy eviction holds the `0
  ExecutorService/Thread.start()` baseline from recon §10.4.
- **Tools unchanged**: protocol-agnostic per recon §8.7; the five
  existing tools (echo, listChildren, readPoint, writePoint,
  bqlQuery) carry over without modification.
- **Auth surface unchanged**: bearer-token check at
  `McpServlet.checkAuth()` (`McpServlet.java:151-168`) covers the
  new endpoint for free.
- **Refactor is bounded**: extracting `Session` is a mechanical
  change. The `cast (SseSession) session` in `handleMessage`
  (post-refactor) is the only transport-specific knowledge in
  `McpServlet`'s SSE branch.

**Negative**

- **Code surface grows ~250 LOC**: estimated based on commit
  scaffolding in `docs/v0.2.0-streamable-http.md`. About 50 LOC
  for `Session.java` + `StreamableSession.java`, ~150 LOC for the
  three new `handleStreamable*` methods in `McpServlet`, ~50 LOC
  for the registry helpers and the new property. Stays well under
  the v0.1.0 baseline of 1 305 LOC of our Java (recon §1.2).
- **Two session-id namespaces coexist**: legacy `sessionId` query
  parameter (SSE) and `Mcp-Session-Id` header (Streamable HTTP)
  must both be understood by `McpSessions`. The two are
  type-tagged via `Session`/`SseSession`/`StreamableSession`, so
  there's no collision risk, but it's an extra cognitive item for
  future maintainers.
- **`GET /mcp` lands as a degenerate stub** that immediately
  closes the SSE response without pushing anything. Spec-allowed
  (server may end the stream when it has nothing to push) but
  contributes nothing functionally; documented as a known gap.

## Alternatives considered

### A. Replace SSE entirely with Streamable HTTP

**Rejected.** Breaks every existing v0.1.0 deployment. niagaramcp
has live users; we don't get to ship a hostile upgrade just because
the spec direction changed.

### B. Use a `ScheduledExecutorService` to sweep idle sessions in the background

**Rejected.** Introduces the codebase's first thread. Adds a
lifecycle concern (start in `serviceStarted()`, shutdown in
`serviceStopped()`, deal with the executor outliving a service
restart). Lazy eviction at acquire time is correct for this access
pattern (hot sessions are touched constantly; cold sessions are
checked when the next request happens to look them up). The trade-
off is a small amount of stale memory between the last access and
the next acquire of any session — acceptable.

### C. Separate servlet `McpStreamableServlet` registered at a second
   `<servlet-mapping>`

**Rejected.** Forces duplication of `checkServiceEnabled`,
`checkAuth`, `sendUnauthorized`, `sendPlain` helpers OR pulls them
into a shared abstract base class (more refactor than the value
gained). One servlet with two transport branches is the minimum-
diff path.

### D. Add `enqueue` to the `Session` interface

**Rejected.** Streamable HTTP doesn't enqueue replies — they're
written to the response stream of the same POST. Putting `enqueue`
on the interface would force `StreamableSession` to either no-op
or throw, both of which leak SSE-specific semantics into the
abstraction. Better to keep `enqueue` as `SseSession`-specific and
have the SSE-handling code-path do its own type cast (see
`docs/v0.2.0-streamable-http.md` line 169-173 for the explicit
guidance).

## Open items / follow-ups

- **Server-initiated push messages on `GET /mcp`**: spec-supported
  channel for server→client notifications (e.g. tool progress
  updates mid-call, sampling requests). Implemented as a degenerate
  immediate-close stub in v0.2.0 because no current tool produces
  mid-call output. Adding real push would resurrect the queue
  pattern for `StreamableSession` — keep it in mind, don't build it
  yet.
- **Streaming response on POST**
  (`Content-Type: text/event-stream` for a long-running tool call):
  spec-optional. v0.2.0 always returns unary `application/json`.
  Would require a tool-side hook for emitting interim events, which
  no current tool needs.
- **OAuth2 resource-server profile** (Bearer JWT validation,
  OAuth-issued tokens): deferred to v0.3.0 or later. The current
  Bearer scheme stays in place. Spec allows server-driven
  `WWW-Authenticate` challenges with `realm`/`as_uri` for OAuth
  bootstrap; out of scope for this ADR.
