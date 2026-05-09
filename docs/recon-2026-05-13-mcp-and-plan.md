# Recon — niagaramcp v0.3.0 (MCP extensions + implementation plan)

**Date:** 2026-05-09. **Companion:** `docs/recon-2026-05-13-niagara-apis.md`
(sections 1–4: Niagara History/Alarms/file-system + YAML decision).
**Brief:** `docs/v0.3.0-implementation-notes.md`.

This file covers sections 5–12 of the brief. Code references are
relative to v0.2.0 (`main` branch, tag `v0.2.0`, last commit
`99cefc1`). Every extension point cites a `path:LINE` from the
v0.2.0-merged tree.

---

## §5 — MCP protocol extensions for v0.3.0

The MCP spec 2025-06-18 (the version we negotiate as of v0.2.0,
`niagaramcp-rt/src/com/niagaramcp/server/McpProtocol.java:30`) defines
two feature surfaces our server doesn't yet implement: **Resources**
and **Prompts**. Both plug into the same JSON-RPC dispatcher; both
require capability advertisement bumps.

### 5.1 Resources — JSON-RPC methods

| Method | Request params | Response shape |
|---|---|---|
| `resources/list` | `{cursor?: string}` | `{resources: [{uri, name, description?, mimeType?}, ...], nextCursor?}` |
| `resources/read` | `{uri: string}` | `{contents: [{uri, mimeType?, text\|blob}, ...]}` |
| `resources/templates/list` | `{cursor?: string}` | `{resourceTemplates: [{uriTemplate, name, description?, mimeType?}, ...]}` |
| `notifications/resources/list_changed` | (no body) | (notification, no reply) — **deferred to v0.4** |
| `notifications/resources/updated` | `{uri}` | (notification) — **deferred to v0.4** |

Pagination via `cursor` is optional in the spec; the v0.3.0
knowledge file is small enough (≤ a few hundred entries on most
stations) that we **return everything in one page** and omit
`nextCursor`. The cursor parameter is accepted (and ignored) for
forward compat.

`resources/read` returns content as a JSON object. For
knowledge-derived resources we serve `mimeType: "application/json"`
(or `"application/x-yaml"` if the operator's knowledge file is YAML
and they specifically request the raw form). Default: serve the
parsed/normalised JSON view regardless of underlying file format
(this avoids leaking the storage choice through the protocol).

### 5.2 Prompts — JSON-RPC methods

| Method | Request params | Response shape |
|---|---|---|
| `prompts/list` | `{cursor?: string}` | `{prompts: [{name, description?, arguments?: [{name, description?, required?}, ...]}, ...]}` |
| `prompts/get` | `{name: string, arguments?: object}` | `{description?, messages: [{role: "user"\|"assistant", content: {type: "text", text}}, ...]}` |

Prompts are server-curated message templates. The client calls
`prompts/get` with optional argument substitutions and gets back a
sequence of messages it can either show to the user or feed
directly into a model turn.

For v0.3.0 the seven prompts from `docs/concepts/04-roadmap.md:57-64`:

- `walkthrough.new_station` — full phase 1–6 walkthrough opener.
- `walkthrough.continue` — resume.
- `walkthrough.verify_types` — equipment-type check.
- `walkthrough.apply_pattern` — import-from-other-station.
- `query.equipment_state` — args: `equipmentId` (string).
- `query.zone_comfort` — args: `spaceId` (string).
- `query.alarm_summary` — args: `since` (ISO datetime, optional).

### 5.3 Capability advertisement

Current advertisement (`niagaramcp-rt/src/com/niagaramcp/server/McpProtocol.java:78-91`):

```java
private static JSONObject buildInitializeResult() {
  JSONObject caps = new JSONObject();
  caps.put("tools", new JSONObject());

  JSONObject info = new JSONObject();
  info.put("name", SERVER_NAME);
  info.put("version", SERVER_VERSION);

  JSONObject result = new JSONObject();
  result.put("protocolVersion", PROTOCOL_VERSION);
  result.put("capabilities", caps);
  result.put("serverInfo", info);
  return result;
}
```

**Change for v0.3.0** — extend `caps`:

```java
caps.put("tools",     new JSONObject());                      // unchanged
caps.put("resources", new JSONObject());                      // empty object = supported, no list-changed/subscribe yet
caps.put("prompts",   new JSONObject());                      // empty object = supported, no list-changed yet
```

Empty-object value (vs `true`, vs missing) is the spec convention
for "supported, no sub-capabilities". Adding `listChanged: true`
to `resources` would commit us to firing
`notifications/resources/list_changed` whenever the knowledge file
changes — deferred per §5.1.

### 5.4 Dispatch in `McpProtocol.handle()`

Current dispatch (`McpProtocol.java:48-70`) has six branches:
`initialize`, `notifications/initialized`, `ping`, `tools/list`,
`tools/call`, `else → ERR_METHOD_NOT_FOUND`. v0.3.0 adds five
methods. Insert before the `if (isNotification)` fallthrough at
`McpProtocol.java:67`:

```java
if ("resources/list".equals(method)) {
  return ok(id, buildResourcesList(params));               // new
}
if ("resources/read".equals(method)) {
  return ok(id, buildResourceRead(params));                // new
}
if ("resources/templates/list".equals(method)) {
  return ok(id, buildResourceTemplatesList(params));       // new
}
if ("prompts/list".equals(method)) {
  return ok(id, buildPromptsList(params));                 // new
}
if ("prompts/get".equals(method)) {
  return ok(id, buildPromptGet(params));                   // new
}
```

Pattern matches the existing `buildToolsList(registry)` /
`callTool(registry, params)` helpers — same style, same
`RpcException`-based error path.

### 5.5 New helper classes (analogous to `ToolRegistry`)

The v0.2.0 baseline has `ToolRegistry` at
`niagaramcp-rt/src/com/niagaramcp/server/ToolRegistry.java:29-44`
populated in `BMcpPlatformService.serviceStarted()`
(`BMcpPlatformService.java:101-107`).

For Resources and Prompts, mirror the pattern:

- **`ResourceProvider`** — generates the list of static resources
  (`niagara://overview`, `niagara://kinds/catalog`) and the list of
  resource templates (`niagara://equipment/{id}`,
  `niagara://spaces/{id}`, etc.). Backed by `KnowledgeStore` for the
  per-id reads.
- **`PromptRegistry`** — `LinkedHashMap<String, Prompt>` analogous
  to `ToolRegistry`'s `LinkedHashMap<String, Tool>`. Each `Prompt`
  has `name()`, `description()`, `arguments()` (the JSON-Schema-ish
  arg list), `render(JSONObject args) → JSONArray<message>`.

Both registered at `serviceStarted()` next to the tool registrations,
exposed via `BMcpPlatformService.getResourceProvider()` and
`getPromptRegistry()` static accessors (mirroring
`getRegistry()` at `BMcpPlatformService.java:166-168`).

### 5.6 `Session` interface — does it need new methods?

Inspect post-v0.2.0 `Session`
(`niagaramcp-rt/src/com/niagaramcp/server/Session.java:25-46`):

```java
public interface Session {
  String getSessionId();
  boolean isInitialized();
  void markInitialized();
  boolean isClosed();
  void close();
  void touch();
}
```

Resources and Prompts are stateless from the session's perspective —
they don't need to track per-session subscription state in v0.3.0
(we deferred `notifications/resources/list_changed` and
subscriptions). **No `Session` changes needed.**

If we later add subscription support (`resources/subscribe`,
`resources/unsubscribe`), the `Session` interface gains
`addResourceSubscription(uri)` / `removeResourceSubscription(uri)` —
straightforward extension. Out of scope for v0.3.0.

---

## §6 — Existing code extension points

### 6.1 Tool registry — concrete plug-in points

`BMcpPlatformService.serviceStarted()` lines
`BMcpPlatformService.java:101-107`:

```java
ToolRegistry r = new ToolRegistry();
r.register((Tool) new EchoTool());
r.register((Tool) new ListChildrenTool());
r.register((Tool) new ReadPointTool());
r.register((Tool) new WritePointTool());
r.register((Tool) new BqlQueryTool());
REGISTRY = r;
```

Add v0.3.0 tools to the same block:

```java
// State-inspection / walkthrough read
r.register((Tool) new GetOverviewTool(...));
r.register((Tool) new InspectComponentTool());
r.register((Tool) new FindComponentsByTypeTool());
r.register((Tool) new GetSlotsTool());

// Knowledge — write side
r.register((Tool) new CreateSpaceTool(knowledgeStore));
r.register((Tool) new UpdateSpaceTool(knowledgeStore));
r.register((Tool) new CreateEquipmentTypeTool(knowledgeStore));
r.register((Tool) new UpdateEquipmentTypeTool(knowledgeStore));
r.register((Tool) new CreateEquipmentTool(knowledgeStore));
r.register((Tool) new UpdateEquipmentTool(knowledgeStore));
r.register((Tool) new BulkCreateEquipmentTool(knowledgeStore));
r.register((Tool) new AssignPointToEquipmentTool(knowledgeStore));
r.register((Tool) new CreateStandalonePointTool(knowledgeStore));
r.register((Tool) new ValidateKnowledgeTool(knowledgeStore));

// Knowledge — management
r.register((Tool) new GetKnowledgeSummaryTool(knowledgeStore));
r.register((Tool) new FindUnmappedComponentsTool(knowledgeStore));
r.register((Tool) new ExportKnowledgeTool(knowledgeStore));
r.register((Tool) new ImportKnowledgeTool(knowledgeStore));
r.register((Tool) new ReloadKnowledgeTool(knowledgeStore));     // operator-edited file

// Search via knowledge
r.register((Tool) new FindEquipmentTool(knowledgeStore));
r.register((Tool) new FindInSpaceTool(knowledgeStore));
r.register((Tool) new FindPointsTool(knowledgeStore));

// Niagara coverage
r.register((Tool) new ReadHistoryTool());
r.register((Tool) new GetActiveAlarmsTool());
r.register((Tool) new GetAlarmHistoryTool(knowledgeStore));
```

**Count:** 5 (v0.2) + 23 (v0.3) = 28 tools registered. The
hardcoded list is fine — 28 lines stays readable.

### 6.2 Knowledge store lifecycle

The `KnowledgeStore` is a long-lived singleton, parallel to
`ToolRegistry`. Lifecycle:

1. **`serviceStarted()`** — load the knowledge file:
   ```java
   String path = getKnowledgeFilePath();        // new property accessor
   File   file = (path == null || path.isEmpty())
                  ? new File(new File(Sys.getNiagaraUserHome(), "niagaramcp"),
                             "knowledge.yaml")
                  : new File(path);
   KnowledgeStore ks = new KnowledgeStore(file);
   ks.load();                                   // creates empty if absent
   KNOWLEDGE = ks;                              // static volatile, like REGISTRY
   ```
2. **`serviceStopped()`** — `KNOWLEDGE = null;` (and any in-flight
   write completes via the lock the store holds).
3. **No background thread** — per ADR-0001 baseline (§3.7 of the
   companion file). All file I/O is on the request thread that
   triggered the write.
4. **`changed(Property, Context)`** at `BMcpPlatformService.java:124-134`
   gains a branch: if `p == knowledgeFilePath && serviceIsRunning`,
   reload the store from the new path.

Storage as `static volatile KnowledgeStore KNOWLEDGE` mirrors the
existing `static volatile ToolRegistry REGISTRY` at
`BMcpPlatformService.java:84`. Same access pattern via
`BMcpPlatformService.getKnowledge()` static accessor.

### 6.3 New properties on `BMcpPlatformService`

The current `@NiagaraProperties` block at
`BMcpPlatformService.java:40-46` has 6 properties. Add:

```java
@NiagaraProperty(name = "knowledgeFilePath",        type = "String",  defaultValue = "\"\""),
@NiagaraProperty(name = "knowledgeAutoBackup",      type = "boolean", defaultValue = "true"),
@NiagaraProperty(name = "knowledgeBackupCount",     type = "int",     defaultValue = "10")
```

`""` = use default `${niagaraUserHome}/niagaramcp/knowledge.yaml`.

Field declarations follow the pattern at
`BMcpPlatformService.java:53-71` (one Property + getter + setter
each).

### 6.4 `McpProtocol.handle()` — five new dispatch branches

See §5.4. Adding 5 branches at
`niagaramcp-rt/src/com/niagaramcp/server/McpProtocol.java:67`. Each
branch: 3 lines (one `if`, one `return ok(id, ...)`).

The 5 helper methods (`buildResourcesList` etc.) live in
`McpProtocol` itself — same shape as
`buildInitializeResult()` (`McpProtocol.java:78-91`),
`buildToolsList(registry)` (`McpProtocol.java:93-107`),
`callTool(registry, params)` (`McpProtocol.java:109-143`).

Estimated growth in `McpProtocol.java`: ~120 LOC (current LOC: 174).

### 6.5 History tool — call-site sketch

`ReadHistoryTool.call(JSONObject args)` pseudo-code:

```java
String  ord    = args.getString("ord");
String  fromS  = args.getString("from");        // ISO datetime or epoch ms (string)
String  toS    = args.optString("to", null);    // optional, defaults to now
int     limit  = args.optInt("limit", DEFAULT_LIMIT);
String  agg    = args.optString("aggregation", null);  // null|avg|min|max|count
int     bucket = args.optInt("bucketSec", 0);

BAbsTime from  = parseAbsTime(fromS);
BAbsTime to    = (toS == null) ? BAbsTime.now() : parseAbsTime(toS);

// Resolve point or extension
BObject  obj = BOrd.make(ord).get();
BHistoryExt ext;
if (obj instanceof BHistoryExt) {
  ext = (BHistoryExt) obj;
} else if (obj instanceof BControlPoint) {
  BControlPoint p = (BControlPoint) obj;
  ext = findHistoryExt(p);                      // walk children for instanceof BHistoryExt
  if (ext == null) throw new IllegalArgumentException(
      "Point has no history extension: " + ord);
} else {
  throw new IllegalArgumentException(
      "Ord does not resolve to a BControlPoint or BHistoryExt: " + ord);
}

BHistoryService svc   = (BHistoryService) Sys.getService(BHistoryService.TYPE);
BHistorySpace   space = svc.getHistoryDb();
HistorySpaceConnection conn = space.getConnection(null);
try {
  HistoryCursor cursor = conn.timeQuery(ext.getHistory(), from, to);
  // walk cursor with timeout + limit; aggregate if requested
  // emit JSON {records: [...], rowCount, truncated}
} finally {
  conn.close();
}
```

Key Niagara API references (verbatim from companion file §1):
- `Sys.getService(BHistoryService.TYPE)` — service lookup.
- `BHistoryService.getHistoryDb()` — the station-local space.
- `BHistorySpace.getConnection(Context)` — per-call session.
- `HistorySpaceConnection.timeQuery(BIHistory, BAbsTime, BAbsTime)`.

### 6.6 Alarms tools — call-site sketch

`GetActiveAlarmsTool.call(args)`:

```java
JSONObject filter   = args.optJSONObject("filter"); // {sourceOrdPrefix?, priority?, ackState?, alarmClass?}
int        limit    = args.optInt("limit", DEFAULT_LIMIT);

BAlarmService     svc   = (BAlarmService) Sys.getService(BAlarmService.TYPE);
BAlarmDatabase    db    = svc.getAlarmDb();
AlarmDbConnection conn  = (AlarmDbConnection) db.getConnection(null);
try {
  Iterable<BAlarmRecord> open = conn.getOpenAlarms();   // see §2.8 TBD on return type
  JSONArray out = new JSONArray();
  int rows = 0;
  for (BAlarmRecord rec : open) {
    if (!matches(rec, filter)) continue;
    if (rows >= limit) break;
    out.put(toJson(rec));
    rows++;
  }
  // emit {alarms: out, count: rows, truncated: ...}
} finally {
  conn.close();
}
```

`GetAlarmHistoryTool.call(args)` adds a `from`/`to` time range and
uses `conn.timeQuery(from, to)` instead of `getOpenAlarms()`. If
`equipmentId` is provided, resolve via `KnowledgeStore` (§2.7 of
companion) and pass `BOrdList` to filter.

### 6.7 Files touched summary (estimate)

| File | Δ LOC (approx) | Action |
|---|---:|---|
| `niagaramcp-rt/src/com/niagaramcp/server/BMcpPlatformService.java` | +60 | 3 new properties, knowledge-store boot, accessors |
| `niagaramcp-rt/src/com/niagaramcp/server/McpProtocol.java` | +130 | 5 new dispatch branches + helpers |
| `niagaramcp-rt/src/com/niagaramcp/server/KnowledgeStore.java` | +400 (new) | YAML+JSON read/write, backup, audit |
| `niagaramcp-rt/src/com/niagaramcp/server/KnowledgeModel.java` | +200 (new) | POJO hierarchy |
| `niagaramcp-rt/src/com/niagaramcp/server/yaml/YamlReader.java` | +180 (new) | tokenizer + parser |
| `niagaramcp-rt/src/com/niagaramcp/server/yaml/YamlWriter.java` | +150 (new) | emitter |
| `niagaramcp-rt/src/com/niagaramcp/server/ResourceProvider.java` | +250 (new) | URI resolution + content gen |
| `niagaramcp-rt/src/com/niagaramcp/server/PromptRegistry.java` | +60 (new) | registry + Prompt interface |
| `niagaramcp-rt/src/com/niagaramcp/server/prompts/*.java` | +250 (new) | 7 prompt impls |
| `niagaramcp-rt/src/com/niagaramcp/server/tools/*.java` | +1400 (new) | 23 new tools, ~60 LOC each avg |
| `niagaramcp-rt/niagaramcp-rt.gradle.kts` | +2 | maybe add `:alarm-rt`, `:history-rt` deps |

Total est. **~3 100 LOC of Java** added. Jar grows ~50–80 KB,
matching the roadmap's "~30–50 KB" estimate (`04-roadmap.md:161-162`)
on the low end and overshooting on the high end. Within budget for
"jar < 200 KB" (`04-roadmap.md:248`) — current 120 523 B + 80 KB =
~200 KB, just within.

---

## §7 — Walkthrough write tools (3 representative sketches + pattern)

The brief asked for three concrete tools. The common pattern they all
follow:

1. Validate args.
2. Lock the knowledge store (single-writer).
3. Mutate the in-memory `KnowledgeModel`.
4. Run validation rules (§4.6 of companion).
5. Persist (atomic write + backup + audit log entry).
6. Return success/error JSON.

### 7.1 `createSpace`

**Args** (from `docs/concepts/03-workflow.md:219`):

```json
{
  "id":      "string (required, kebab-case)",
  "name":    "string (required)",
  "aliases": "array of string (optional)",
  "type":    "string (optional, e.g. building/floor/zone/parking)",
  "parent":  "string (optional, ref to existing space.id)"
}
```

**Body sketch:**

```java
public String call(JSONObject args) throws Exception {
  String id    = args.getString("id");
  String name  = args.getString("name");
  List<String> aliases = JsonUtil.toStringList(args.optJSONArray("aliases"));
  String type   = args.optString("type", "");
  String parent = args.optString("parent", null);

  validateId(id);                              // kebab-case
  if (parent != null && !parent.isEmpty()) {
    if (knowledge.getSpace(parent) == null)
      throw new IllegalArgumentException("Unknown parent space: " + parent);
  }

  Space s = new Space(id, name, aliases, type, parent, /*description*/null, /*bounds*/null);
  knowledge.lock();
  try {
    if (knowledge.getSpace(id) != null)
      throw new IllegalArgumentException("Space already exists: " + id);
    knowledge.addSpace(s);
    knowledge.persist("createSpace", id, sessionId);
  } finally {
    knowledge.unlock();
  }
  return "{\"ok\":true,\"id\":\"" + id + "\"}";
}
```

The `knowledge.persist(action, id, sessionId)` does:
- Atomic write (§3.3 of companion).
- Backup rotation (§3.4 of companion).
- Audit log line (§3.5 of companion).

### 7.2 `bulkCreateEquipment`

**Args:**

```json
{
  "items": [
    {
      "id":       "string",
      "name":     "string",
      "aliases":  "array of string",
      "type":     "string (ref equipment_types.id)",
      "space":    "string (ref spaces.id)",
      "ord":      "string (Niagara ord)",
      "points":   "object (role -> ord), optional"
    },
    ...
  ]
}
```

**Body sketch (atomicity is the key concern — all-or-nothing):**

```java
public String call(JSONObject args) throws Exception {
  JSONArray items = args.getJSONArray("items");
  if (items.length() == 0)
    throw new IllegalArgumentException("items must be non-empty");

  // Validate all entries before mutating
  List<Equipment> staged = new ArrayList<>(items.length());
  for (int i = 0; i < items.length(); i++) {
    JSONObject e = items.getJSONObject(i);
    Equipment   eq = parseEquipment(e);
    validate(eq);                              // type/space refs + ord syntax
    staged.add(eq);
  }

  knowledge.lock();
  try {
    // Second pass: check id collisions against the locked store
    for (Equipment eq : staged) {
      if (knowledge.getEquipment(eq.id) != null)
        throw new IllegalArgumentException("Equipment already exists: " + eq.id);
    }
    for (Equipment eq : staged) {
      knowledge.addEquipment(eq);
    }
    knowledge.persist("bulkCreateEquipment", "+" + staged.size(), sessionId);
  } finally {
    knowledge.unlock();
  }
  return "{\"ok\":true,\"created\":" + staged.size() + "}";
}
```

The two-pass design (validate-then-commit) ensures atomicity:
either all N items are added, or none are. The lock makes the
"check id collision then add" critical section race-free.

### 7.3 `assignPointToEquipment`

**Args:**

```json
{
  "equipmentId": "string (required)",
  "role":        "string (required, e.g. supply_air_temp)",
  "ord":         "string (Niagara ord, required)"
}
```

**Body sketch:**

```java
public String call(JSONObject args) throws Exception {
  String eqId = args.getString("equipmentId");
  String role = args.getString("role");
  String ord  = args.getString("ord");

  validateOrdSyntax(ord);                      // BOrd.make() catches illegal

  knowledge.lock();
  try {
    Equipment eq = knowledge.getEquipment(eqId);
    if (eq == null) throw new IllegalArgumentException("Unknown equipment: " + eqId);
    eq.points.put(role, ord);                  // LinkedHashMap, role is key
    knowledge.persist("assignPointToEquipment", eqId + "/" + role, sessionId);
  } finally {
    knowledge.unlock();
  }
  return "{\"ok\":true,\"equipment\":\"" + eqId + "\",\"role\":\"" + role + "\"}";
}
```

The role does not need to be in `equipment_types[].typical_points` —
operators can add ad-hoc roles, and a later
`equipment_types`-edit can absorb the role into the catalog. This
keeps walkthrough flexible.

### 7.4 Pattern summary

All write tools share:

- **Lock-protected mutation** with a single `lock()`/`unlock()` pair.
- **Validate-then-commit** order so no partial state on failure.
- **One persist call per tool invocation** — never multiple writes
  per tool (atomic file replace either succeeds or fails).
- **Audit log line** with action name + primary id + sessionId.
- **JSON success response** — `{"ok": true, ...}` shape.

Estimated per-tool LOC:
- Args parsing + validation: ~20–30.
- Mutation logic: ~10–15.
- Persistence call: 1 line.
- Schema (`schemaJson()`): ~15.
- Description (Russian, comparable to existing tools): ~5.
- Boilerplate (header, class, name(), description(), schemaJson(), call()): ~15.

Total ~70 LOC per tool. 23 tools × 70 ≈ 1610 LOC. Slightly above
the §6.7 estimate of 1400 — refine as commits land.

---

## §8 — Niagara state inspection (read tools for walkthrough)

The walkthrough phase 2–4 (`docs/concepts/03-workflow.md:58-167`)
needs four read-side tools. All use Niagara API surfaces already
available in `:baja` and `:control-rt`.

### 8.1 `getOverview()`

**Returns** top-level station structure summary:

```json
{
  "stationName": "afimall-main",
  "drivers":   ["BACnet", "Modbus", "OPC"],          // immediate children of /Drivers
  "services":  ["AlarmService", "HistoryService", ...],
  "componentCounts": {                                // by Niagara type, top-N
    "BNumericPoint":     1240,
    "BBooleanPoint":      420,
    "BAhuController":      12,
    "...": "..."
  },
  "topFolders": ["Drivers", "Logic", "History", ...]   // immediate children of root
}
```

**Implementation:**

```java
BComponent station = Sys.getStation();
JSONObject result  = new JSONObject();
result.put("stationName", station.getName());

// Top-level folders
BComponent drivers = (BComponent) station.get("Drivers");
JSONArray  drArr   = new JSONArray();
if (drivers != null) {
  for (BComponent c : drivers.getChildComponents()) drArr.put(c.getName());
}
result.put("drivers", drArr);

// Component counts via BQL
BITable<BObject> tbl = (BITable) BOrd.make("station:|bql:select * from c").get();
TableCursor<?>   cur = tbl.cursor();
Map<String,Integer> counts = new HashMap<>();
while (cur.next()) {
  BComponent c = (BComponent) cur.get();
  String t = c.getType().getTypeSpec().toString();
  counts.merge(t, 1, Integer::sum);
}
// sort and emit top 20
```

`Sys.getStation()` returns the `BStation` root.
`BComponent.get(String slot)` returns the child by slot name.
`BComponent.getChildComponents()` is already used at
`niagaramcp-rt/src/com/niagaramcp/server/tools/ListChildrenTool.java:79`.

The BQL approach for component-counting reuses the existing
`BqlQueryTool` plumbing under the hood; a more direct alternative
walks the slot tree recursively (no BQL dependency) and counts
in a single visitor.

### 8.2 `inspectComponent(ord)`

**Returns** detailed component info:

```json
{
  "ord":       "station:|slot:/...",
  "name":      "AHU_1_1",
  "displayName": "Roof AHU 1.1",
  "type":      "control:NumericPoint",
  "parent":    "station:|slot:/Drivers/BACnet/Roof",
  "facets":    {"units": "°C", "precision": "1"},
  "slots":     [...]           // see getSlots
}
```

**Niagara API:**
- `BOrd.make(ord).get()` — already used.
- `BObject.getType().toString()` — type spec.
- `BComplex.getSlots()` — `SlotCursor` over all slots.
- `BComplex.getSlotFacets(Slot)` — facets per slot.
- `BComponent.getDisplayName(Context)` — already used.

The "parent" link uses `BComponent.getPropertyInParent()` (or
`getPropertyInParentComponent()` — see anchor list in companion §1).

### 8.3 `findComponentsByType(typeName)`

**Args:** `{typeName: "control:NumericWritable"}` or short form
`{typeName: "BNumericWritable"}`.

**Returns:**

```json
{
  "type":  "control:NumericWritable",
  "count": 87,
  "items": [
    {"ord": "...", "name": "...", "displayName": "..."},
    ...
  ],
  "truncated": false
}
```

**Implementation:** delegate to BQL via the existing infrastructure:

```java
String typeSpec = canonicalize(typeName);     // "BNumericWritable" → "control:NumericWritable"
String bql = "station:|bql:select * from " + typeSpec;
BITable<BObject> table = (BITable) BOrd.make(bql).get();
// walk cursor, emit ord+name+displayName per row, cap at limit
```

Same pattern as `BqlQueryTool` at
`niagaramcp-rt/src/com/niagaramcp/server/tools/BqlQueryTool.java:84-117`,
just shaped for the simpler output. The canonicalisation helper
strips a leading `B` and prepends a default module prefix (e.g.
`control:`) when missing — small lookup table for the common cases.

### 8.4 `getSlots(ord)`

**Returns** the `SlotCursor` walk (`BComplex.getSlots()`) projected to:

```json
{
  "ord": "...",
  "slots": [
    {
      "name":    "out",
      "kind":    "Property",
      "type":    "baja:StatusNumeric",
      "flags":   ["READONLY"],
      "facets":  {"units": "°C"},
      "value":   "23.5 {ok}"        // toString of current value, when applicable
    },
    {
      "name":    "set",
      "kind":    "Action",
      "...":     "..."
    }
  ]
}
```

**Niagara API:**
- `BComplex.getSlots()` — `SlotCursor`.
- `Slot.getName()`.
- `Slot.getType()` (`Type`).
- `Slot.isProperty()` / `isAction()` / `isTopic()`.
- `BComplex.getSlotFacets(Slot)`.
- For Properties: `BComplex.get(Slot)` → current `BValue`.

`SlotCursor` is at `javax.baja.sys.SlotCursor` (extracted to
`/tmp/njdoc/doc/javax/baja/sys/SlotCursor.html`); it iterates with
`next()` / `getSlot()`.

### 8.5 Estimated LOC for state inspection tools

| Tool | LOC est. |
|---|---:|
| `GetOverviewTool` | 80 |
| `InspectComponentTool` | 70 |
| `FindComponentsByTypeTool` | 60 |
| `GetSlotsTool` | 90 |
| **Subtotal** | **300** |

Within the §6.7 budget. No new Niagara dependencies needed — all
classes are in `:baja` (`BStation`, `BComplex`, `Slot`, `SlotCursor`,
`BFacets`) or `:control-rt` (`BControlPoint`).

---

## §9 — Resource URI scheme design

The brief asked which scheme to use for resource URIs. MCP spec
2025-06-18 imposes no scheme restriction beyond "must be a valid URI".

### 9.1 Scheme choice — `niagara://`

**Recommendation: `niagara://`** for these reasons:

1. **Self-describing in client logs.** A user looking at an MCP
   client log sees `niagara://overview` and immediately knows it
   came from this server.
2. **Matches Niagara native ord scheme.** Niagara's own ords use
   `station:|slot:/...`; our scheme nesting alongside is intuitive
   for BMS-aware integrators.
3. **Disambiguates from `https://`/`file://`/`data://`.** No client
   will try to fetch `niagara://` over HTTP.
4. **Doesn't conflict with anything reserved** (it's not in IANA's
   registered URI scheme list as of the spec's reference date).

**Rejected alternatives:**

- `mcp://` — too generic; would collide with other servers.
- Plain path (`/overview`) — not a URI, fails the spec's must-be-URI
  check on strict clients.
- `urn:niagaramcp:...` — verbose, no upside over the bespoke scheme.

### 9.2 Static resources (always loaded)

| URI | Purpose | mimeType |
|---|---|---|
| `niagara://overview` | Top-level station summary derived from `knowledge.station` + sums of equipment_types/equipment/spaces. | `application/json` |
| `niagara://kinds/catalog` | Full `equipment_types` section. | `application/json` |

These are the "spine" resources (`docs/concepts/04-roadmap.md:50-51`)
that an MCP client should load on connect to ground its context.

### 9.3 Resource templates (RFC 6570)

MCP spec specifies RFC 6570 URI templates. Our templates use Level 1
syntax (simple variable expansion):

| Template | Example expansion | Purpose |
|---|---|---|
| `niagara://equipment/{id}` | `niagara://equipment/ahu-pa-e-01` | Single equipment, full record + linked space + linked equipment_type. |
| `niagara://spaces/{id}` | `niagara://spaces/parking-sector-e` | Single space, plus list of equipment in it. |
| `niagara://standalone-points/{id}` | `niagara://standalone-points/co2-pa-e-01` | Single standalone sensor record. |

**Variable shape:** `{id}` is a string; client sends the literal
substitution. No type ambiguity since our knowledge ids are
kebab-case.

### 9.4 History resource — query-string approach

The brief raised the question of `niagara://history/{ord}?from=&to=`.
RFC 6570 supports query parameters via Level 3 syntax:

```
niagara://history/{ord}{?from,to,limit}
```

This expands to `niagara://history/station%3A%7Cslot%3A%2F...%2Fout?from=2026-05-09T00:00:00Z&to=2026-05-09T23:59:59Z`.

**Concern:** the ord itself contains URI-reserved characters (`:`,
`|`, `/`) which must be percent-encoded into the path component.
This works but produces ugly URIs. An alternative is to **not expose
history as a resource template** and only expose it via the
`readHistory` tool. Resources are good for static-ish facts;
history is a query result.

**Recommendation:** **drop history from resources.** Use the
`readHistory` tool for time-bounded data fetches. Equipment and
space resources can include a `historyAvailable: true` hint and
operators/AI clients invoke the `readHistory` tool with the relevant
ord. This sidesteps the URI-encoding ergonomics and keeps the
resource catalog stable (URIs don't depend on time-of-day).

### 9.5 Resource read response shape

```json
{
  "contents": [
    {
      "uri":      "niagara://equipment/ahu-pa-e-01",
      "mimeType": "application/json",
      "text":     "{\"id\":\"ahu-pa-e-01\",\"name\":\"AHU Паркинг E-01\", ...}"
    }
  ]
}
```

The `text` field is a stringified JSON representation. The double-
serialisation (JSON-as-string-inside-JSON) is the spec's contract —
it lets the same field carry plain text, JSON, YAML, etc. without
changing shape.

### 9.6 URI parsing (server side)

Pseudo-code at the dispatch site:

```java
String uri = params.getString("uri");
if (!uri.startsWith("niagara://")) throw new RpcException(...);
String path = uri.substring("niagara://".length());

if (path.equals("overview"))         return overviewResource();
if (path.equals("kinds/catalog"))    return kindsCatalogResource();
if (path.startsWith("equipment/"))   return equipmentResource(path.substring(10));
if (path.startsWith("spaces/"))      return spaceResource(path.substring(7));
if (path.startsWith("standalone-points/"))
                                     return standalonePointResource(path.substring(18));

throw new RpcException(ERR_INVALID_PARAMS, "Unknown resource: " + uri);
```

`path.substring(N)` is sufficient for our Level-1 templates; if we
later add Level-3 query strings, swap to a real URI parser
(`java.net.URI`).

---

## §10 — Implementation plan (12 atomic commits)

Refining `docs/concepts/04-roadmap.md:164-191` with file paths and
LOC estimates. Each commit must build green via
`./gradlew :niagaramcp-rt:assemble --configure-on-demand` and
preserve v0.2.0's SSE+Streamable transports.

### Commit 1 — `docs(adr): 0002 Semantic enrichment layer design`

- New: `docs/adr/0002-semantic-layer.md`.
- Formalises the four concept docs (`docs/concepts/01..04`) into
  ADR style: Status, Context, Decision (knowledge-as-yaml; bundled
  yaml emitter; static lists in BMcpPlatformService; resources +
  prompts capability bumps), Consequences, Alternatives.
- ~250 LOC markdown.

### Commit 2 — `feat: knowledge file format + KnowledgeStore + YAML I/O`

- New: `KnowledgeStore.java`, `KnowledgeModel.java`,
  `yaml/YamlReader.java`, `yaml/YamlWriter.java`,
  `yaml/YamlException.java`.
- Edit: `niagaramcp-rt/niagaramcp-rt.gradle.kts` only if a new
  `:alarm-rt`/`:history-rt` dep is needed (decided at this stage by
  trying to compile §6 history/alarm imports; defer if §6 commits
  haven't been touched yet — leave gradle alone).
- ~1 100 LOC.

### Commit 3 — `feat: BMcpPlatformService wires KnowledgeStore`

- Edit: `BMcpPlatformService.java` — add 3 new properties
  (`knowledgeFilePath`, `knowledgeAutoBackup`, `knowledgeBackupCount`),
  load store in `serviceStarted()`, expose `getKnowledge()`.
- Edit: `niagaramcp-rt/module-permissions.xml` — no change (per §3
  of companion).
- ~80 LOC.

### Commit 4 — `feat: walkthrough read tools (4)`

- New: `tools/GetOverviewTool.java`, `tools/InspectComponentTool.java`,
  `tools/FindComponentsByTypeTool.java`, `tools/GetSlotsTool.java`.
- Edit: `BMcpPlatformService.serviceStarted()` registers them.
- ~300 LOC.

### Commit 5 — `feat: walkthrough write tools — basic (5)`

- New: `tools/CreateSpaceTool.java`, `UpdateSpaceTool.java`,
  `CreateEquipmentTypeTool.java`, `UpdateEquipmentTypeTool.java`,
  `CreateEquipmentTool.java`.
- Each follows the §7.4 pattern; relies on `KnowledgeStore.lock()`.
- ~350 LOC.

### Commit 6 — `feat: walkthrough write tools — advanced (5)`

- New: `tools/UpdateEquipmentTool.java`, `BulkCreateEquipmentTool.java`,
  `tools/AssignPointToEquipmentTool.java`, `CreateStandalonePointTool.java`,
  `ValidateKnowledgeTool.java`.
- ~370 LOC.

### Commit 7 — `feat: knowledge management tools (5)`

- New: `tools/GetKnowledgeSummaryTool.java`,
  `FindUnmappedComponentsTool.java`, `ExportKnowledgeTool.java`,
  `ImportKnowledgeTool.java`, `ReloadKnowledgeTool.java`.
- ~300 LOC.

### Commit 8 — `feat: search tools using knowledge (3)`

- New: `tools/FindEquipmentTool.java`, `FindInSpaceTool.java`,
  `FindPointsTool.java`.
- ~200 LOC.

### Commit 9 — `feat: history tool via BHistoryExt`

- New: `tools/ReadHistoryTool.java`.
- Edit: `niagaramcp-rt.gradle.kts` — add `api(":history-rt")`
  if compile fails without it (likely needed; transitive may suffice).
- ~140 LOC + 2 LOC gradle.

### Commit 10 — `feat: alarm tools via BAlarmService`

- New: `tools/GetActiveAlarmsTool.java`, `GetAlarmHistoryTool.java`.
- Edit: `niagaramcp-rt.gradle.kts` — add `api(":alarm-rt")` if needed.
- ~250 LOC + 2 LOC gradle.

### Commit 11 — `feat: MCP resources + prompts`

- New: `ResourceProvider.java`, `Prompt.java` (interface),
  `PromptRegistry.java`, `prompts/Walkthrough*.java` (4),
  `prompts/Query*.java` (3).
- Edit: `McpProtocol.java` — 5 new dispatch branches + 5 helpers,
  capability advertisement extended.
- Edit: `BMcpPlatformService.java` — register provider/registry.
- ~600 LOC.

### Commit 12 — `docs: v0.3.0 release notes + smoke-test runbook`

- Edit: `CHANGELOG.md` — v0.3.0 section (Added/Changed/Deprecated).
- Edit: `_SMOKE_TEST.md` — append v0.3.0 runbook (knowledge create,
  walkthrough roundtrip, resources/list, prompts/list, readHistory,
  getActiveAlarms).
- Edit: `README.md`, `README.ru.md` — Resources/Prompts sections,
  walkthrough quickstart curl example.
- ~250 LOC docs.

### Cumulative LOC across commits

| Layer | LOC |
|---|---:|
| Knowledge store + YAML | ~1 100 |
| 4 read tools | ~300 |
| 23 write/management/search tools | ~1 200 |
| History tool | ~140 |
| Alarm tools | ~250 |
| Resources + prompts | ~600 |
| Service-level wiring | ~80 |
| ADR | ~250 |
| Docs | ~250 |
| **Total** | **~4 170 LOC** |

That's heavier than the §6.7 estimate (~3 100). Refine as it lands —
the first few commits will calibrate the per-tool average and we
can prune.

Each commit independently buildable: yes. Each commit preserves
v0.2.0 endpoints: yes (the only edits to existing v0.2.0 files are
additive — new dispatch branches, new property, new registrations).

---

## §11 — Open decision points needing human input

### 11.1 Default `knowledgeFilePath`

**Question:** What's the default value of the
`knowledgeFilePath` property?

**Options:**

- **A.** Empty string (means "use built-in default
  `${niagaraUserHome}/niagaramcp/knowledge.yaml`"). Recommended.
  Operators don't need to configure anything for the default
  experience; the `getKnowledgeFilePath()` accessor handles the
  empty-string substitution.
- **B.** Hard-coded absolute path like
  `C:\Users\...\niagaramcp\knowledge.yaml`. Rejected — non-portable.
- **C.** Niagara `file:` ord like
  `file:^niagaramcp/knowledge.yaml`. Would let operators move it via
  Workbench. Adds a `BFileSpace` lookup dependency we don't currently
  need.

**Recommendation: A.** Same pattern as v0.2.0's `apiToken`
(empty string default, accessor returns sensible value).

### 11.2 Default equipment_types in jar

**Question:** Should the module ship with a default
`equipment_types` catalog (AHU/Chiller/Pump/etc.) bundled in the
jar, or start empty?

**Options:**

- **A.** Ship empty. Walkthrough or import populates. Pros: no
  opinionated assumptions, every project's vocabulary differs.
  Cons: every fresh deployment starts cold.
- **B.** Ship a small default set (~5–10 generic types). Pros: AI
  has something to reason about on first walkthrough. Cons: defaults
  may not match local terminology; merging defaults vs operator
  edits requires care.
- **C.** Ship a default *importable* set in `resources/sample-knowledge.yaml`
  inside the jar — operator runs `importKnowledge` to opt in.

**Recommendation: C.** Reads as "we have suggestions but you're in
control". The resource is one classpath load; ~50 LOC to expose via
`resources/list` (which makes it discoverable for any AI client).
The first walkthrough can open this resource and ask "do you want
me to import the standard types?".

### 11.3 Resources require auth?

**Question:** Should `resources/list` and `resources/read` require
the same Bearer-token auth as everything else, or be open
(anonymous read of structure)?

**Options:**

- **A.** Same Bearer-token gate. Resources contain station
  structure, equipment names, ords — sensitive. Recommendation.
- **B.** Open (anonymous read). Simplifies AI-client onboarding
  but leaks structure to anyone who can reach the endpoint.

**Recommendation: A.** Auth applies uniformly. Per recon §13.4 of
v0.2.0 baseline, auth sits above transport — Resources inherit the
same regime as Tools for free; not adding auth is the work, not
subtracting it.

### 11.4 YAML or JSON as primary on first creation?

**Question:** When the file doesn't exist and we create it, do we
write YAML or JSON?

**Options:**

- **A.** YAML — operator-friendly default per `04-roadmap.md:128`.
  Recommendation.
- **B.** JSON — zero-LOC parser path (free).

**Recommendation: A.** Operator UX wins. The dual-format read
(§4.4 of companion) means JSON-first deployments still work
automatically for those who prefer it.

### 11.5 Prompts — server-curated vs user-customisable?

**Question:** Are the 7 prompts hard-coded in jar, or also
operator-editable via knowledge file?

**Options:**

- **A.** Hard-coded. v0.3.0 ships with the spec'd 7. Operators who
  want different ones file an issue / submit a PR. Recommendation.
- **B.** Editable via knowledge.yaml `prompts: [...]` section.
  Adds ~200 LOC of templating logic.

**Recommendation: A.** v0.3.0 ships hard-coded; v0.4 can add
`prompts:` section to schema if there's demand.

### 11.6 `aliases` — case-sensitive matching in search tools?

**Question:** When `findEquipment("ahu 1.5")` searches aliases,
does it match `"AHU 1.5"`?

**Options:**

- **A.** Case-insensitive lowercase match. Russian + English
  aliases mix; "ВУ" vs "ву" should match. Recommendation.
- **B.** Case-sensitive exact match. Rejected — operator typing
  varies.

**Recommendation: A.** Use `String.toLowerCase(Locale.ROOT)` on
both query and stored alias for comparison. Document explicitly.

### 11.7 Reload trigger on file change

**Question:** How do operators trigger a reload after manually
editing `knowledge.yaml`?

**Options:**

- **A.** Explicit `reloadKnowledge` tool. Operator runs it. Simple,
  no thread cost. Recommendation.
- **B.** File watcher (auto-reload). Adds first-thread baseline
  break (recon companion §3.7).

**Recommendation: A.** Document the tool prominently; clients
should expose it as a one-click action.

### 11.8 `mcpKnowledgeFilePath` vs `knowledgeFilePath`

**Question:** Property naming — prefix all knowledge-related
properties with `mcp` or not?

The existing v0.2.0 properties at
`niagaramcp-rt/src/com/niagaramcp/server/BMcpPlatformService.java:40-46`
use mixed conventions: `enabled`, `showLog`, `status`, `apiToken`,
`sseHeartbeatSec`, `mcpSessionIdleTimeoutSec`. The mcp-prefix only
appears where the name would otherwise be ambiguous.

**Options:**

- **A.** No prefix: `knowledgeFilePath`, `knowledgeAutoBackup`,
  `knowledgeBackupCount`. Recommendation — `knowledge*` is
  self-disambiguating.
- **B.** Prefix: `mcpKnowledgeFilePath`. Verbose without benefit.

**Recommendation: A.**

---

## §12 — Cannot-answer follow-ups (runtime experiments needed)

| # | Question | Why static can't answer | Resolution path |
|---|---|---|---|
| 1 | Return type of `AlarmDbConnection.getOpenAlarms()` — `Iterable<BAlarmRecord>` vs `BAlarmRecord[]` vs cursor? | Javadoc anchor list shows method name + arg pattern but not return type. | `unzip -p .../alarm-rt.jar javax/baja/alarm/AlarmDbConnection.class \| javap -p` once on Windows; first build fixes it via compile error if wrong. |
| 2 | Same for `getAckPendingAlarms`, `getOpenAlarmSources`, `getAlarmsForSource`, `scan`, `timeQuery` on `AlarmDbConnection`. | Same. | Same. |
| 3 | `HistorySpaceConnection.timeQuery(...)` exact return — `HistoryCursor` directly, or wrapped? | Anchor shows method+args, not return. | Same. |
| 4 | Whether `:alarm-rt` and `:history-rt` are needed in `niagaramcp-rt.gradle.kts` (currently only `:baja`, `:control-rt`, `:web-rt`) or whether they're transitive. | Static — can be guessed from package locations but only the gradle-resolution graph confirms. | Try compile in commit 9/10; add deps only if compile fails. |
| 5 | Behaviour of `BHistoryService.getHistoryDb()` on a station with no history service configured. | Runtime config dependent. | Test on a station without history. Defensive: catch `NullPointerException`/etc., return a clean error. |
| 6 | Behaviour of `getOpenAlarms()` under a 100K-alarm DB load. | Performance characteristic, only measurable. | Load-test on a real station; the in-process row cap (§2.4 of companion) bounds output regardless. |
| 7 | Whether `Sys.getNiagaraUserHome()` returns a writable path on a JACE-2 controller with strict file-system layout. | Hardware dependency. | Test on JACE-2 if such hardware available; otherwise document "tested on Win + Linux Supervisor only". |
| 8 | File-watcher behaviour across NTFS / SMB shares / local disk. | OS+driver. | Out of scope for v0.3.0 (no watcher). |
| 9 | Whether `BAlarmDatabase.bqlQuery(OrdTarget, OrdQuery)` actually returns a `BITable` compatible with our `BqlQueryTool`. | The signature returns the parent type; subclasses may be expected. | Quick smoke via existing `BqlQueryTool` against alarm db ord — already possible in v0.2.0. |
| 10 | Whether the YAML emitter's quoting handles all real Russian / Cyrillic strings without surprises. | Encoding edge cases. | Test in commit 2 with the example file from `docs/concepts/02-format.md:308-336`; round-trip identity required. |
| 11 | Whether the embedded `com.niagaramcp.json` library handles UTF-8 surrogate pairs in `JSONObject.toString()`. | Library-specific. | Spot-test in commit 11 (resources contain Russian aliases). The library is `org.json` based (recon-2026-05-09 §3.4); should be fine. |
| 12 | Module reload behaviour on `knowledge.yaml` edit while station running — does the in-memory `KnowledgeStore` get refreshed? | The plan says: only via explicit `reloadKnowledge` tool. Confirm operator UX is acceptable. | Manual test in commit 7. |
| 13 | Whether MCP clients (Claude Desktop, Continue.dev) consume `application/x-yaml` mimeType in `resources/read`. | Client compatibility. | Test in commit 11; fall back to `application/json` if unknown. |
| 14 | Whether `BHistoryExt` instances always live at slot name `historyExt` or whether projects use other names. | Naming convention. | Defensive code (companion §1.7) walks children for any `BHistoryExt instanceof` — already covers both. |
| 15 | Behaviour of `point.get("historyExt")` when slot doesn't exist — null or exception? | Niagara-specific. | The defensive code path uses `instanceof` which handles both cases uniformly. |
| 16 | Performance of validation on a 5000-equipment knowledge file. | Scale. | Run in commit 7. ~5000 entries × ~5 ref checks each = 25K lookups, hash-backed — should be milliseconds. |
| 17 | Whether the `bcLog` System.out shim (recon-2026-05-09 §10.3) handles UTF-8 correctly when logging Cyrillic ids. | JVM file.encoding setting. | Set `-Dfile.encoding=UTF-8` if logs garble. Defer fix to v0.4 logging refactor (`CHANGELOG.md:73-74`). |
| 18 | What's actually in `BAlarmRecord.toString()` — useful for debug or noisy? | Niagara default. | Log a sample on first commit-10 run. |
| 19 | Whether `Sys.getStation().get("Drivers")` is the canonical way to find the drivers folder, or whether it's localised. | Possible localisation. | Probably canonical; test on a Russian-locale station. |
| 20 | Whether a fresh `MCP` service install (no prior run) triggers any "knowledge file missing" error to the user, or silently creates an empty default. | Lifecycle. | Test in commit 3. The plan calls for "auto-create on first write" (`04-roadmap.md:24-25`); on serviceStarted with missing file, log info-level "no knowledge yet, will be created on first walkthrough write". |

---

*End of file 2.* Implementation order in §10 supersedes the
12-step list at `docs/concepts/04-roadmap.md:164-191` only by
adding file:line specificity — the structure and ordering match.
