# ADR-0002: Semantic enrichment layer (knowledge.yaml + Resources + Prompts)

## Status

**PROPOSED** — 2026-05-09

## Context

niagaramcp v0.2.0 surfaces a Niagara station's raw component tree
through five Tools (`echo`, `listChildren`, `readPoint`, `writePoint`,
`bqlQuery`) over both SSE and Streamable HTTP transports. AI clients
can read points, walk slot trees, and run BQL — but the station has
no semantic layer. It knows that
`station:|slot:/Drivers/BACnet/Roof/AHU_1_1` is a `BAhuController`;
it does **not** know that this is "the rooftop unit serving parking
sector E", or that the operator typically calls it "руфтоп 1.1", or
that its slot `SAT` is the supply-air temperature.

Without a semantic layer, an AI agent answering "какая температура на
паркинге сектор E" must brute-force the slot tree — slow, fragile,
and unreliable. Three standard solutions all fail in practice on
typical Russian BMS projects:

1. **Project Haystack tagging** — requires integrators to tag during
   the project, which doesn't happen.
2. **Strict naming convention** — fragile, not enforced.
3. **Manual Excel registry** — created once, never updated.

All three demand effort *before* AI is useful. This is chicken-and-egg:
no model → AI useless → no incentive to build model.

The four concept documents under `docs/concepts/` propose the
solution: **AI bootstraps the semantic model itself, through
walkthrough**. The operator already has the knowledge in their head;
AI extracts it via guided dialogue and writes it to a structured
file. After one walkthrough (1–2 hours per typical site), AI is ready
for natural-language operational queries.

The recon (`docs/recon-2026-05-13-niagara-apis.md` and
`-mcp-and-plan.md`, totalling 2 115 lines, 48 file:line refs) verified
that all the Niagara API surfaces required (`BHistoryExt`,
`HistorySpaceConnection`, `BAlarmService`, `Sys.getNiagaraUserHome`)
exist in 4.15.3.28, that file-system writes from the module need no
new permissions, and that a custom YAML emitter fits comfortably
within ~330 LOC. Zero blockers were found.

This ADR formalises the design before code lands.

## Decision

### 1. Knowledge file at `${niagaraUserHome}/niagaramcp/knowledge.yaml`

The semantic model lives in a single YAML file under the user-home
directory. Default path is computed lazily from
`Sys.getNiagaraUserHome()` (recon §3.1); operators can override via
the `knowledgeFilePath` property on `BMcpPlatformService` (empty
string = use default, mirrors v0.2.0's `apiToken` accessor pattern).

Schema documented in `docs/concepts/02-format.md`. Top-level sections:
`station`, `spaces`, `equipment_types`, `equipment`, `points`. Schema
version 1; future versions trigger backed-up auto-migration on load.

### 2. Dual-format read, YAML default on write

The store reads either YAML or JSON (sniffing the first non-blank,
non-`#` character — `{` ⇒ JSON, otherwise YAML, per recon §4.4).
First-creation writes YAML (operator UX wins per
`04-roadmap.md:128`). Subsequent writes preserve the current format.
A custom YAML reader/writer ships in-tree (~330 LOC budget, hard cap
500); if YAML edge cases blow the cap, fallback to JSON-only via the
already-embedded `com.niagaramcp.json` library.

### 3. 23 new Tools registered alongside existing 5

The Tool plug-in pattern from v0.1.0 (`Tool` interface at
`niagaramcp-rt/src/com/niagaramcp/server/tools/Tool.java:25-33`,
populated in `BMcpPlatformService.serviceStarted()` at
`niagaramcp-rt/src/com/niagaramcp/server/BMcpPlatformService.java:97-112`)
extends to 28 tools total: 4 read-side walkthrough helpers, 10
walkthrough writers, 5 management tools, 3 search tools, 1 history
tool, 2 alarm tools. All share the existing `ToolRegistry` and
dispatch through `McpProtocol.callTool()`.

### 4. MCP Resources + Prompts (capability bumps)

`McpProtocol.handle()` (`McpProtocol.java:39-76`) gains five new
JSON-RPC dispatch branches: `resources/list`, `resources/read`,
`resources/templates/list`, `prompts/list`, `prompts/get`. The
`buildInitializeResult()` capability advertisement
(`McpProtocol.java:78-91`) extends from `{tools:{}}` to
`{tools:{}, resources:{}, prompts:{}}`. Both new feature surfaces
follow the same Bearer-auth gate as Tools (recon §11.3 confirmed) —
auth sits above transport per ADR-0001 §13.4.

URI scheme: `niagara://`. Six resources total: 2 static
(`overview`, `kinds/catalog`), 3 templated
(`equipment/{id}`, `spaces/{id}`, `standalone-points/{id}`), 1
static sample (`samples/standard-types`). History is **not** a
resource — operators use the `readHistory` tool instead, sidestepping
URI-encoding ergonomics for ords (recon §9.4).

7 hard-coded Prompts shipped: 4 walkthrough flows
(`walkthrough.new_station`, `walkthrough.continue`,
`walkthrough.verify_types`, `walkthrough.apply_pattern`) + 3 query
shortcuts (`query.equipment_state`, `query.zone_comfort`,
`query.alarm_summary`).

### 5. Lazy reload, no file watcher

The plan (`docs/concepts/03-workflow.md:268-271`) considered a
file-system watcher to auto-reload `knowledge.yaml` after manual
operator edits. **Rejected** — introducing a watcher creates the
codebase's first thread, breaking the
zero-`ExecutorService`/`Thread.start()` baseline maintained since
v0.1.0 (per recon §10.4 of v0.2.0 baseline). Operators trigger
reload via the explicit `reloadKnowledge` tool (recon §11.7
confirmed).

### 6. No new Niagara permissions

Recon §3.4 verified that direct `java.io.File` operations on
`Sys.getNiagaraUserHome()` work without `module-permissions.xml`
changes — the `FILE_ACCESS` permission gates the `BFileSpace`
abstraction, not direct OS file I/O. The current pair
(`NETWORK_COMMUNICATION` + `UNAUTHENTICATED_ACCESS` at
`niagaramcp-rt/module-permissions.xml:7-19`) is sufficient.

### 7. Backup rotation, audit log, atomic write

Each persist operation:

1. Writes new content to `knowledge.yaml.tmp.<millis>`.
2. Rotates the existing file to
   `knowledge.yaml.bak.<isoStamp>`.
3. `Files.move(tmp, target, ATOMIC_MOVE, REPLACE_EXISTING)`.
4. Appends a JSON line to `knowledge.audit.log` (action, id,
   sessionId, timestamp).
5. Prunes backups beyond `knowledgeBackupCount` (default 5).

The atomic-move primitive is reliable on NTFS and POSIX (recon §3.3).
The audit log is single-writer (the `KnowledgeStore` synchronizes all
mutations) so lock-free append is safe.

## Consequences

**Positive**

- AI agents can answer natural-language operational questions in one
  round-trip after walkthrough, without slot-tree brute force.
- Operator knowledge becomes a first-class artefact, version-
  controllable, exportable between sites.
- Backward compatibility absolute: v0.2.0 endpoints unchanged; new
  Tools/Resources/Prompts are purely additive.
- No new threads, no new deps, no new permissions — the v0.1.0/v0.2.0
  operational baseline holds.

**Negative**

- Code surface grows ~4 200 LOC (commit-by-commit budget in recon
  §10). Jar grows ~30–80 KB (estimate range from
  `04-roadmap.md:161-162`).
- 23 new tools means `tools/list` returns a longer list — clients
  must page their UI accordingly. The MCP spec doesn't restrict tool
  count.
- Knowledge file consistency depends on operator discipline plus the
  `validateKnowledge` tool. There's no schema-on-write enforcement
  beyond required-field checks; operator can produce semantically
  inconsistent state (e.g., orphan equipment) which `validateKnowledge`
  flags but doesn't block.
- Two storage formats (YAML on write, dual on read) means slight
  ambiguity for tools that snapshot the file content directly.
  Mitigated by always using the parsed/normalised model
  internally; format only matters at the file boundary.

## Alternatives considered

### A. Use snake-yaml as a third-party dependency

Rejected per recon §4.5. snake-yaml is ~600 KB jar with its own
classloader concerns; the v0.1.0 "no new dependencies" policy is
load-bearing for module isolation in shared Niagara stations. A
~330-LOC hand-written emitter for our schema subset is well within
budget.

### B. JSON-only storage

Considered per recon §4.6. Pros: zero-LOC parser (already embedded).
Cons: operators editing manually find JSON's quote/brace/comma syntax
hostile; concept docs explicitly preferred YAML
(`04-roadmap.md:128`). Compromise: dual-format read, YAML write
default, JSON write available via `exportKnowledge(format)`.

### C. File-system watcher for auto-reload

Considered per `03-workflow.md:268-271`. Rejected per §11.7 of recon:
breaks the zero-thread baseline. Mitigation: explicit
`reloadKnowledge` tool. Operator UX: one-click action in the AI
client after manual edit.

### D. Bundled equipment_types catalog

Considered per §11.2 of recon. Rejected: opinionated defaults won't
match local terminology, and merge semantics with operator edits add
complexity. Compromise: ship a sample knowledge file as a Resource
(`niagara://samples/standard-types`) that operators can opt-in via
`importKnowledge(mode: merge|replace)`. The first walkthrough can
discover this resource and ask the operator whether to import.

### E. Subscription-based resource updates

The MCP spec supports `notifications/resources/list_changed` and
`resources/subscribe`. Deferred to v0.4+. Implementing now would
require:

1. A change log on the `KnowledgeStore`.
2. Per-session subscription tracking in `Session`.
3. Push delivery via the existing SSE/Streamable channel.

(1) is small (one event-list field), (2) requires extending the
`Session` interface (recon §5.6 — currently no change needed), (3)
re-introduces the "server-initiated push" question that ADR-0001 §3
deferred. Net: ~150 LOC of new infrastructure for a feature that has
no immediate consumer (no current MCP client subscribes to
list-changed for resource trees as small as ours). Worth doing once
either (a) we have a real subscriber or (b) v0.4 introduces
schedule/program writes that benefit from notifying clients.

### F. RBAC per tool / per resource

Deferred to v0.4+ per `04-roadmap.md:80-81`. Currently any caller
with the Bearer token has equal access to all tools, including
`writePoint` and the new walkthrough writers. Mitigation in this
release: tools that mutate knowledge log every action to
`knowledge.audit.log` with sessionId, providing post-hoc forensics
even without per-action authorization.

## Open items / follow-ups

- **Schema migration** — only `schema_version: 1` exists. Reserve a
  `Map<Integer, Migration>` in `KnowledgeStore` for v2-onward; do
  not implement a migration in v0.3.0.
- **Resource subscription** — see Alternative E above; revisit
  when first subscriber appears.
- **Per-tool RBAC** — see Alternative F; v0.4 work item.
- **Sample catalog content** — initial sample ships with 5 generic
  equipment types (AHU, Chiller, Pump, RTU, FCU). Operators are
  encouraged to extend / replace.
- **`:alarm-rt` and `:history-rt` gradle deps** — recon §6.7 noted
  these may be needed in `niagaramcp-rt.gradle.kts`. Decided at
  first compile in commit 9/10; add only if compile actually fails.

## Cross-references

- `docs/concepts/01-concept.md` — problem framing
- `docs/concepts/02-format.md` — file schema spec
- `docs/concepts/03-workflow.md` — walkthrough flow
- `docs/concepts/04-roadmap.md` — v0.3.0 scope
- `docs/recon-2026-05-13-niagara-apis.md` §1–§4
- `docs/recon-2026-05-13-mcp-and-plan.md` §5–§12
- `docs/adr/0001-streamable-http-transport.md` — auth-above-transport
  precedent
