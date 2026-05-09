# Recon: niagaramcp v0.1.0 — module structure for Streamable HTTP design

## Mission

We need to add Streamable HTTP MCP transport to `niagaramcp` alongside
the existing SSE+Bearer transport. Before designing the new transport,
inventory the current code: how is the SSE servlet registered, how is
JSON-RPC dispatched, how are tools wired in, where does Bearer auth
live, what could be reused vs what's coupled to SSE.

This is **read-only reconnaissance**. Do not modify any file, do not
run gradle, do not compile, do not commit. Use only `view`, `grep`,
`find`, `git log`, and platform-equivalent tools.

## Pre-flight

1. Working directory is the repo root containing `niagaramcp-rt/`,
   `README.md`, `LICENSE`, `niagara-module.xml`. Confirm with `ls`.
2. Confirm git tree is clean: `git status` should show only the recon
   doc as untracked when you finish. If anything else is dirty, stop
   and report.
3. Read first, in this order: `README.md`, `_CODE_REVIEW.md`,
   `_SMOKE_TEST.md`, `RELEASE.md`, `CHANGELOG.md`. They contain the
   intent of the project; the rest of the recon validates them against
   actual code.
4. Note: the repo was just rebranded from `platMcp`/`ru.bccontrol`/`BCControl`
   to `niagaramcp`/`com.niagaramcp`/"niagaramcp contributors".
   `_CODE_REVIEW.md` deliberately retains old names as a historical
   document. When grepping for current names use `niagaramcp`,
   `com.niagaramcp`. If you find new code referencing the old names
   anywhere outside `_CODE_REVIEW.md`, flag it.

## Output

Single markdown report at `docs/recon-2026-05-09.md` (create the
`docs/` directory if it doesn't exist). Every claim must include a
`path/to/file.java:LINE` reference. Quote key signatures verbatim
(class declarations, method signatures, important conditionals). If
something can't be answered from static inspection, say so. Aim for
800-1500 lines.

## Sections

### 1. Repo structure & build

- Directory tree of the repo, depth 4, excluding `build/`, `.gradle/`,
  `.git/`. On Windows: `Get-ChildItem -Recurse -Depth 4 | …`. On Linux:
  `find . -maxdepth 4 -not -path './build/*' …`.
- LOC by area: our Java (`niagaramcp-rt/src/com/niagaramcp/server/**`),
  embedded JSON (`niagaramcp-rt/src/com/niagaramcp/json/**`), config
  files, docs.
- `niagaramcp-rt/niagaramcp-rt.gradle.kts` — full quote. Identify:
  Niagara API jars on classpath, target Java version, Gradle plugins,
  signing configuration, `moduleManifest` settings, custom tasks.
- `niagara-module.xml` — full quote.
- Java version actually used: examine the `.java` files for any
  Java-9+ syntax (`var`, lambdas-in-interfaces, switch expressions,
  records, sealed). Flag if anything would break Java 8 compat.

### 2. Module declaration & permissions

- `niagaramcp-rt/module-include.xml` — full quote. Which classes
  exported, which packages public, which BModule registered.
- `niagaramcp-rt/module-permissions.xml` — full quote. What permissions
  the module requests.
- `niagaramcp-rt/module.palette` — full quote. What components
  show up in the Niagara palette.
- `niagaramcp-rt/module.lexicon` — head 30 lines + total line count.
  What labels are localised.

### 3. Java package layout

For `niagaramcp-rt/src/com/niagaramcp/server/` and
`niagaramcp-rt/src/com/niagaramcp/server/tools/`:

- List every `.java` file with one-line description
- For each file: class name, parent class / interfaces implemented,
  number of methods, total LOC
- Identify the **entry point** — which class is registered as the
  Niagara service / servlet, and how (via BModule, BService, or
  BWbServlet annotation?)

For `niagaramcp-rt/src/com/niagaramcp/json/` (the embedded library):

- Total file count, total LOC
- Top-level package class names — list 5-10 most prominent
- Origin: scan headers/license comments for clues. Is this org.json,
  Jackson stripped, json-simple, Gson, or something else? Quote any
  copyright lines verbatim.

### 4. Servlet registration & URL routing

The current SSE endpoint is at `https://<host>/platMcp/sse` (note
old name in client config; new module exposes either `/niagaramcp/sse`
or whatever the servlet declares). Find and document:

- Which class extends `BWbServlet` (or `WbServlet`, or registers via
  `BHttpAccept`)?
- The exact URL pattern this class handles. Where is it declared?
  Possible places:
  - `@NiagaraType` / `@NiagaraProperty` annotations
  - `module-include.xml` `<wbservlet>` entries
  - `getDefaultDirectoryName()` / `getDirectoryName()` overrides
  - Hardcoded in `doGet` / `doPost`
- How does this servlet name relate to the URL `/platMcp/...`? Is it
  `getServletName()`, the module name, or computed?
- Is there a single servlet handling both SSE long-poll and JSON-RPC
  POST, or are there two?

### 5. MCP protocol dispatcher

Find where incoming JSON-RPC requests are parsed and dispatched:

- Method signatures: `parseRequest(...)`, `dispatch(...)`,
  `handleMethod(...)` — whatever names exist
- Supported MCP methods (`initialize`, `tools/list`, `tools/call`,
  `notifications/*`, etc.) — list each with the file:line of its
  handler
- Version of MCP protocol implemented (look for `protocolVersion` in
  initialize response)
- How tools are registered (static list? service locator? annotation
  scanning?) — file:line

### 6. SSE transport specifics

The transport is the part that needs to be cleanly separated from
the protocol so the new Streamable HTTP transport can plug in beside
it. Document carefully:

- Where the SSE response is opened (the `Content-Type:
  text/event-stream` write, the flush loop)
- How a single SSE connection is mapped to an MCP "session" (is there
  a session ID? a Map keyed on what?)
- How requests-from-client are received over this transport (separate
  POST? URL parameter? something else?)
- How keep-alive / reconnect is handled
- How concurrent connections are tracked
- Any threading concerns (does the SSE flush loop hold a Niagara
  worker thread? is there a dedicated executor?)

### 7. Bearer authentication

- Where is the `Authorization: Bearer …` header parsed? File:line.
- How is the token compared / validated? Static config? Looked up
  somewhere?
- Where is the configured token stored (Niagara property bag?
  config file? hardcoded?)
- What happens on auth failure? (HTTP 401? closed connection?
  silent drop?)
- Does auth apply uniformly to every MCP request, or is initialization
  treated differently?

### 8. Tools

For each of `Tool.java`, `ListChildrenTool.java`, `ReadPointTool.java`,
`WritePointTool.java`:

- Class declaration verbatim
- Tool name as advertised to MCP (the string a client uses in
  `tools/call`)
- Parameter schema (input fields, types)
- How it accesses Niagara objects:
  - `BComponent`, `BControlPoint`, `BHistoryExt` references
  - Path resolution (Ord lookups? string parsing?)
  - Read vs write semantics
- Error handling: what gets thrown / returned on missing path,
  permission denied, type mismatch
- Threading: is this called on a Niagara worker thread? Does it
  block on point reads? Does it handle slow points (history reads)?

### 9. Embedded JSON library

- What's the public API surface? Quote one or two main entry-point
  signatures (`JsonReader.read(...)`, `JsonObject.put(...)`, etc.)
- Does it support streaming, or is it materialise-all-at-once?
- Why was it embedded vs depending on a Maven artefact? Look for
  comments explaining this. Suspect reasons: Java 8 compat, Niagara
  classloader isolation, code-signing requirement.

### 10. Lifecycle, threading, logging

- Module lifecycle: which class extends `BModule`, what happens in
  `started()` / `stopped()` (or equivalent)?
- Servlet lifecycle: when is the servlet instantiated, when does it
  go away?
- Logging: which logging framework is in use? `Logger.getLogger` with
  Niagara's own logging? `org.slf4j`? Quote one log call as evidence.
- Any explicit `ExecutorService`, `ScheduledExecutorService`, or
  `Thread` creation? File:line each.

### 11. Tests

Is there any `src/test/` or `test/` directory? Any `@Test`
annotations in source? Niagara has its own test framework
(`BTestNg`?) — check for it.

If no tests exist, say so explicitly and note in section 12 that
adding tests for the new transport will be a parallel concern.

### 12. Documentation files — full quotes

Quote in full:

- `_CODE_REVIEW.md` (the historical pre-rebrand review — interesting
  to see what the reviewer found)
- `_SMOKE_TEST.md` (this is the deployment / verification procedure
  we will need to extend for the new transport)
- `RELEASE.md`
- `CHANGELOG.md` (current state)

For each: note specifically anything that talks about extension
points, architectural intent, or known limitations.

### 13. Decision-relevant observations for Streamable HTTP design

This is the recon's payload. For each item: file:line + one paragraph
explaining why it matters for adding the new transport.

Specifically look for:

- **Is the JSON-RPC dispatcher coupled to the SSE transport?** Or is
  there already a clean transport-agnostic core that just needs a
  second adapter? If coupled, sketch what would need extracting.
- **Session model** — does the existing code assume one
  session = one SSE connection? If yes, Streamable HTTP (which can
  carry multiple requests per session over multiple connections)
  needs a different session lookup. Where would `Mcp-Session-Id`
  header be inspected, generated, validated?
- **Servlet registration** — can the same `BWbServlet` handle two
  URL paths (`/sse` and `/mcp`), or do we need a second servlet
  class?
- **Auth layer placement** — does Bearer auth check sit above the
  transport (good — Streamable HTTP gets it for free), or inside
  the SSE handler (bad — needs duplication or refactor)?
- **Tool dispatch** — are tools called on the request thread, or
  marshalled to a worker? Streamable HTTP has different lifecycle:
  POST returns either immediately (unary) or streams chunked SSE.
  The tool execution model needs to fit both.
- **Backward compat** — can `/sse` keep working while `/mcp` exists
  alongside? What state, if any, would conflict?
- **Dependencies** — does the module already import any
  HTTP-streaming primitives we'd need (chunked transfer encoding,
  flush control, write-and-flush)? If not, what does Niagara's API
  give us?
- **Java 8 compat for new code** — Niagara module is Java 8.
  Streamable HTTP doesn't strictly require anything modern. But if
  we want, e.g., `CompletableFuture` for async tool calls, confirm
  it's available.

For each observation: rank as **blocker / refactor needed / clean
addition** for Streamable HTTP work.

### 14. Cannot-answer follow-ups

Any question above that requires running code, opening a Niagara
station, or observing live network traffic — list here. Don't try to
guess. Examples that are likely runtime-only:
- Actual URL the servlet ends up at (depends on Niagara station
  config)
- Behaviour under concurrent connections (need load test)
- Whether tools survive a station restart (need restart)

## Hard constraints

- Read-only. No edits, no `gradle build`, no compilation, no commits.
- Don't run any code or test framework.
- Static inspection only. If something requires runtime, defer to §14.
- Don't modify the documentation files you read.
- Don't trust file paths from old documents (`_CODE_REVIEW.md` may
  reference `platMcp` paths that no longer exist).

## When you're done

1. The report exists at `docs/recon-2026-05-09.md` and is complete.
2. `git status` shows only this one untracked file.
3. Print to stdout: total LOC of the recon, count of file:line
   references, count of "blocker" items in §13.