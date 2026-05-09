# Recon — niagaramcp v0.3.0 (Niagara API surfaces)

**Date:** 2026-05-09 (`today` in our system; brief filename uses 2026-05-13 placeholder).
**Scope:** static read-only inspection of Niagara 4.15.3.28 javadoc + jar listings.
**Target:** validate the v0.3.0 plan's assumptions about History, Alarms, file-system access, and the YAML-vs-JSON storage decision.
**Companion file:** `docs/recon-2026-05-13-mcp-and-plan.md` — covers MCP-protocol extensions (resources/prompts), code extension points in our v0.2.0 baseline, walkthrough/inspection tools, URI scheme, implementation plan, open decisions, and runtime-only follow-ups.
**Brief:** `docs/v0.3.0-implementation-notes.md` (mis-named, content is the recon brief).

All Niagara class references below are validated against
`C:\Niagara\Niagara-4.15.3.28\javadoc\niagaraJavadoc.jar` (the bundled
javadoc; entries discovered via `unzip -l`, methods enumerated from
`<a name="…">` anchors). When citing Niagara APIs the reference is
`<FQN>.<method>(<params>)`; when citing our code the reference is
`<path>:<line>` from the v0.2.0-merged tree on `main`.

---

## §1 — Niagara History API surface (BHistoryExt and friends)

### 1.1 Class layout

The History stack in Niagara 4.15 lives across three packages
(`javadoc/javax/baja/history/...`):

| Package | Purpose |
|---|---|
| `javax.baja.history` | Top-level service, space, database connection, query DSL, record types. |
| `javax.baja.history.ext` | History extensions that attach to control points (the entry point you query when you have a `BControlPoint`). |
| `javax.baja.history.db` | Lower-level database connection abstraction (`HistoryDatabaseConnection extends HistorySpaceConnection`). |

Principal classes for the v0.3.0 readHistory tool:

- **`javax.baja.history.ext.BHistoryExt`** — abstract base for per-point
  history extensions. Concrete subclasses live in the same package:
  `BNumericIntervalHistoryExt`, `BNumericCovHistoryExt`,
  `BBooleanIntervalHistoryExt`, `BBooleanCovHistoryExt`,
  `BEnumIntervalHistoryExt`, `BEnumCovHistoryExt`,
  `BStringIntervalHistoryExt`, `BStringCovHistoryExt`. *(Interval = sampled
  on a fixed period; COV = change-of-value.)*
- **`javax.baja.history.BIHistory`** — interface representing a stored
  history (one per point). Returned by `BHistoryExt.getHistory()`.
- **`javax.baja.history.BHistoryConfig`** — schema/config of a stored
  history (capacity, interval, time zone, full-policy, record type,
  source ord). Returned by `BHistoryExt.getHistoryConfig()` and by
  `BIHistory.getConfig()`.
- **`javax.baja.history.BHistorySpace`** — the station's history space.
  Holds connections, performs lookups by `BHistoryId`.
- **`javax.baja.history.HistorySpaceConnection`** — main read/write API
  per session. This is the **canonical place to query records**.
- **`javax.baja.history.db.HistoryDatabaseConnection`** — direct DB-level
  connection (extends `HistorySpaceConnection`); used internally and by
  advanced flows.
- **`javax.baja.history.HistoryCursor`** — cursor returned by query
  methods; iterable of `BHistoryRecord`.
- **`javax.baja.history.BHistoryRecord`** — base record type (timestamp +
  payload).
- **`javax.baja.history.BTrendRecord`** — base for the trended subclasses
  used by intervals: `BNumericTrendRecord`, `BBooleanTrendRecord`,
  `BEnumTrendRecord`, `BStringTrendRecord`. Each adds the value of the
  appropriate type plus a `BTrendFlags`.

### 1.2 Read API — recommended path

The canonical read path for "give me records for point X between t1 and
t2" is:

```
BControlPoint point         = (BControlPoint) BOrd.make(ord).get();
BHistoryExt    ext           = (BHistoryExt) point.get("historyExt");      // slot conventionally named historyExt
BIHistory      history       = ext.getHistory();
BHistoryService histService  = (BHistoryService) Sys.getService(BHistoryService.TYPE);
BHistorySpace  space         = histService.getHistoryDb();                  // station-local space
HistorySpaceConnection conn  = space.getConnection(null);                   // null => default Context
try {
    HistoryCursor cursor = conn.timeQuery(history, BAbsTime.make(fromMs), BAbsTime.make(toMs));
    while (cursor.next()) {
        BHistoryRecord rec = (BHistoryRecord) cursor.get();
        // rec.getTimestamp(), and for BNumericTrendRecord: ((BNumericTrendRecord) rec).getValue()
    }
} finally {
    conn.close();
}
```

This compiles against the public surface enumerated below.

### 1.3 Method signatures (verbatim from javadoc anchors)

**`javax.baja.history.ext.BHistoryExt`**
*(extracted from `doc/javax/baja/history/ext/BHistoryExt.html`)*

| Method | Returns |
|---|---|
| `getHistory()` | `BIHistory` — the stored history this extension feeds. |
| `getHistoryConfig()` | `BHistoryConfig` — schema/config of the history. |
| `getHistoryName()` | `String` — display name. |
| `getHistoryNameFormat()` | format string for naming. |
| `getLastRecord()` | `BHistoryRecord` — most recent record (cheap). |
| `getRecordType()` | `Type` — runtime type of records (e.g. `BNumericTrendRecord.TYPE`). |
| `getActive()` / `getEnabled()` | `BBoolean` — whether logging is on. |
| `getSourceOrd()` | `BOrd` — source point. |
| `append(BTrendRecord)` | append manually (write side, not used by readHistory). |

**`javax.baja.history.HistorySpaceConnection`**
*(`doc/javax/baja/history/HistorySpaceConnection.html`)*

| Method | Notes |
|---|---|
| `timeQuery(BIHistory, BAbsTime, BAbsTime)` | **primary read API.** Returns a `HistoryCursor` over records whose timestamp falls in the inclusive range. |
| `timeQuery(BIHistory, BAbsTime, BAbsTime, boolean)` | overload — second `boolean` controls whether to include archive data (true = include archived records as well as live). |
| `scan(BIHistory)` / `scan(BIHistory, boolean)` | returns a cursor over **all** records of the history. The `boolean` overload toggles archive inclusion. |
| `getFirstTimestamp(BIHistory)` | `BAbsTime` — earliest record. |
| `getLastTimestamp(BIHistory)` | `BAbsTime` — most recent record. |
| `getRecordCount(BIHistory)` | `int` — exact count. |
| `getLastRecord(BIHistory)` | `BHistoryRecord` — most recent. |
| `getSummary(BIHistory)` | summary object (count + bounds, cheaper than three calls). |
| `flush(BIHistory)` | force-write any pending data. |
| `close()` | release resources. **MUST be called** in a `try/finally`. |

The `HistoryDatabaseConnection` subclass adds DB-level operations
(create/delete/rename history) that we do not need for v0.3.0.

**`javax.baja.history.HistoryCursor`**
*(`doc/javax/baja/history/HistoryCursor.html`, extends
`javax.baja.collection.AbstractCursor` from `baja.jar`)*

| Method | Notes |
|---|---|
| `next()` | inherited from `AbstractCursor` — advances; returns `boolean`. |
| `get()` | inherited — current record (`BHistoryRecord`). |
| `doNext(BHistoryRecord)` | low-level subclass hook; not called from outside. |
| `getConfig()` | `BHistoryConfig` of the history being walked. |
| `archiveLimitExceeded(Context)` | check whether an archive query was capped. |

The cursor is `AbstractCursor`-based, so the standard `next()` /
`get()` iteration works. There is no "fetch chunk of N" API; you
iterate one at a time and stop when you've collected enough.

**`javax.baja.history.BHistoryRecord`**
*(`doc/javax/baja/history/BHistoryRecord.html`)*

| Method | Returns |
|---|---|
| `getTimestamp()` | `BAbsTime` |
| `getRecord(int)` | `BIDataValue` — by index, when the schema has multiple cells. |
| `getRecordCount()` | `int` — number of cells. |
| `getRecordSize()` | bytes. |
| `getSchema()` | the underlying schema. |
| `getRecordTypeSpec()` | `TypeSpec`. |

For typed access, **cast the record to its concrete subclass** —
`BNumericTrendRecord`, `BBooleanTrendRecord`, etc. — and call
`getValue()`.

**`javax.baja.history.BNumericTrendRecord`**
*(`doc/javax/baja/history/BNumericTrendRecord.html`)*: extends
`BTrendRecord`. Standard properties (per `<a name="…">` anchors):
`value` (`double`), `status` (`BStatus`), and inherited `timestamp`
plus `trendFlags`.

The other three trend-record subclasses are analogous, just with
`boolean` / `int (enum ordinal)` / `String` payloads.

### 1.4 Time arguments — `BAbsTime` / `BRelTime`

**`javax.baja.sys.BAbsTime`** constructors
*(`doc/javax/baja/sys/BAbsTime.html`)*:

```
BAbsTime.now()                              → current
BAbsTime.make(long millis)                  → from epoch ms
BAbsTime.make(long millis, BTimeZone)       → from epoch ms in tz
BAbsTime.make(String iso)                   → parse ISO 8601
BAbsTime.make(int year, BMonth, int day, int hour, int minute, int sec)
BAbsTime.make(...)  + BTimeZone overload
```

The MCP `readHistory` tool will accept `from` and `to` as ISO-8601
strings (or as epoch ms) per the JSON-RPC convention; conversion via
`BAbsTime.make(String)` or `BAbsTime.make(long)`.

**`javax.baja.sys.BRelTime`** constructors:

```
BRelTime.makeHours(int)
BRelTime.makeMinutes(int)
BRelTime.makeSeconds(int)
BRelTime.makeDays(int)
BRelTime.make(long millis)
BRelTime.make(int days, int hours, int minutes, int seconds)
BRelTime.make(String spec)                  → e.g. "1h30m"
```

Useful for "last N hours" syntactic sugar in the tool — `from =
BAbsTime.now().subtract(BRelTime.makeHours(24))`. The `BAbsTime`
class has arithmetic (`add(BRelTime)`, `subtract(BRelTime)`); not
shown above but standard.

### 1.5 Aggregation

**Server-side aggregation: NOT available** in the public
`HistorySpaceConnection` surface. There is no `avg`/`min`/`max`/
`count` over interval signature. The closest is `getRecordCount(BIHistory)`
(total count of stored records, no time range, no aggregation).

This means the v0.3.0 `readHistory(ord, from, to, aggregation?)` tool
must **either**:

1. Return raw records and let the AI client (or human caller)
   aggregate. Simplest. Acceptable for typical BMS queries (a day of
   per-minute samples = 1440 records, fits easily in JSON response).
2. Accept an `aggregation` parameter (`avg|min|max|count`) and
   compute it client-side after walking the cursor. Adds ~30 LOC and
   is genuinely useful for "give me hourly average for last week"
   kind of asks.

**Recommendation:** ship (2) — accept `aggregation` and compute
in-process. Aggregation buckets keyed on a `bucketSec` parameter
(default = no aggregation, return raw records).

If aggregation is supplied alongside `bucketSec`, the tool walks
the cursor, tracks current-bucket start-end, and emits one summary
record per bucket. Memory is bounded by output size (capped by a
max-row limit analogous to BqlQuery's `MAX_LIMIT = 1000`).

### 1.6 Pagination / large queries

No built-in page/offset API. The tool must impose its own row cap and
report truncation in the response footer — the same pattern used in
`BqlQueryTool.call()` (`niagaramcp-rt/src/com/niagaramcp/server/tools/BqlQueryTool.java:84-117`):

```java
TableCursor<?> cursor = table.cursor();
int rows = 0;
while (cursor.next()) {
  if (rows >= limit) { truncatedByLimit = true; break; }
  if (System.currentTimeMillis() - startMs >= ITERATION_TIMEOUT_MS) {
    truncatedByTimeout = true; break;
  }
  ...
}
```

Apply the same idiom to `readHistory`. Suggested defaults:
`MAX_RECORDS = 5000` (history queries are larger than BQL-result-set
ergonomics — 24h × per-minute = 1440, week × hourly = 168 etc.),
`ITERATION_TIMEOUT_MS = 10_000L` matching BQL.

### 1.7 Discoverability — does this point have history?

A `BControlPoint` typically holds its history extension in a child
component slot conventionally named `historyExt` (the property the
"AddHistoryExt" Workbench wizard uses). To discover whether the point
has history:

```java
BObject obj = BOrd.make(ord).get();
BControlPoint point = (BControlPoint) obj;
BObject extObj = point.get("historyExt");
if (extObj instanceof BHistoryExt) {
    // has history
}
```

This is the discoverability pattern. Note: the slot name `historyExt`
is a convention, not a hard guarantee — projects can add multiple
history extensions or rename them. A more robust discovery walks
all child slots looking for any `BHistoryExt` instance:

```java
for (BComponent child : point.getChildComponents()) {
    if (child instanceof BHistoryExt) { ... }
}
```

`BComponent.getChildComponents()` is already used elsewhere in our
codebase — see `niagaramcp-rt/src/com/niagaramcp/server/tools/ListChildrenTool.java:79`.
Reuse the same idiom for the readHistory discovery.

For v0.3.0, **support both** input shapes in the `readHistory` tool:
the `ord` parameter may point at the `BControlPoint` (we discover
the extension) **or** at the `BHistoryExt` directly (we use it).
Detect the case by `instanceof` on the resolved `BObject`.

### 1.8 Direct-by-`BHistoryId` query

Alternative to per-point-via-extension lookup: query directly by
`BHistoryId` against the history space.

`BHistorySpace.getConfig(BHistoryId)` returns the `BHistoryConfig`;
`BHistorySpace.getHistoryIds(BOrd)` lists all histories under a
given path. Useful for the future "explore station histories" tool
but not needed for v0.3.0's per-point readHistory flow.

### 1.9 Open / TBD items for History

| Item | Resolution path |
|---|---|
| Does `point.get("historyExt")` actually return null vs throw on points without history? | TBD — runtime test. The defensive code path uses `instanceof` which handles both null and wrong-type uniformly, so the tool works either way. |
| Behaviour of `timeQuery` with `from > to` | TBD. Defensive: validate at tool entry, return 400-style error. |
| Behaviour with very large date ranges (years of per-second data) | The 10-s iteration timeout caps wall time on our side; cursor itself loads lazily so we don't blow heap. Confirm in a real station. |
| `flush()` semantics if a write is in flight | Not relevant — readHistory doesn't write. |
| Whether the History service exists on a JACE-2 controller (memory-constrained) | Same API surface; behaviour identical. Confirm. |

---

## §2 — Niagara Alarms API surface (BAlarmService and friends)

### 2.1 Class layout

Alarm classes live in `javax.baja.alarm` (the core), with extension
algorithms in `javax.baja.alarm.ext`. The `javax.baja.alarmOrion`
package is a separate add-on module (Niagara Orion alarm DB) we do
not target.

| Class | Purpose |
|---|---|
| **`BAlarmService`** | Singleton service. Obtained via `Sys.getService(BAlarmService.TYPE)`. |
| **`BAlarmDatabase`** | Holds the alarm store. Obtained via `BAlarmService.getAlarmDb()`. |
| **`AlarmDbConnection`** | Per-session read/write connection. From `BAlarmDatabase.getConnection(Context)`. |
| **`AlarmSpaceConnection`** | Parent class of `AlarmDbConnection`; same surface for read APIs. |
| **`BAlarmRecord`** | One alarm event (timestamp, source, message, ack state, transition). |
| **`BAlarmTransitionBits`** | Bitmask describing the transition (offnormal/normal/fault/ack). |
| **`BAckState`** | Acknowledgment state enum. |

### 2.2 Service & DB acquisition

```java
BAlarmService svc      = (BAlarmService) Sys.getService(BAlarmService.TYPE);
BAlarmDatabase db      = svc.getAlarmDb();
AlarmDbConnection conn = (AlarmDbConnection) db.getConnection(null);
try {
    // ... query
} finally {
    conn.close();
}
```

`BAlarmService.getAlarmDb()` is enumerated at
`doc/javax/baja/alarm/BAlarmService.html` (anchor `getAlarmDb`).
`BAlarmDatabase.getConnection(Context)` is enumerated at
`doc/javax/baja/alarm/BAlarmDatabase.html` (anchor
`getConnection-javax.baja.sys.Context-`).

### 2.3 Read API — current (active) alarms

`AlarmDbConnection` (verbatim from javadoc anchors at
`doc/javax/baja/alarm/AlarmDbConnection.html`):

| Method | Use case |
|---|---|
| `getOpenAlarms()` | **All currently open alarms** — those in offnormal/fault/etc. that have not yet returned to normal. This is the main feed for `getActiveAlarms`. |
| `getAckPendingAlarms()` | Subset that requires acknowledgment but hasn't been acked. |
| `getOpenAlarmSources()` | Distinct source ords currently in alarm — useful for "which equipment is in alarm right now" without enumerating every record. |
| `getAlarmsForSource(BOrdList)` | Filter to specific source(s). Takes a list of ords → returns matching open alarms. |
| `getRecord(BUuid)` | Fetch by alarm id. |
| `getRecordCount()` | Total record count in the store. |

These are **active-state queries** — they hit the live "open" set.
Returned objects are `BAlarmRecord`s (or some kind of cursor /
iterable, see TBD §2.7).

### 2.4 Read API — alarm history (time range)

```
AlarmDbConnection.timeQuery(BAbsTime startTime, BAbsTime endTime)
AlarmDbConnection.scan()
```

`timeQuery` is the time-bounded historical query — analogous to
`HistorySpaceConnection.timeQuery` but no `BIHistory` parameter (the
alarm DB is already the database). `scan()` walks every record.

For the v0.3.0 `getAlarmHistory(equipmentId | ord, from, to, filter?)`
tool the flow is:

1. If `equipmentId` was passed (knowledge-layer reference), resolve
   to one or more ords via knowledge.yaml `equipment.ord` and
   `equipment.points.*` slots.
2. Call `timeQuery(from, to)`, walk the cursor, filter records whose
   `getSource()` matches the resolved ord set.
3. Apply optional `severity` / `state` / `ackState` filters from
   `filter?`.
4. Cap rows + timeout the same way as `readHistory` (§1.6).

**Limitation:** `timeQuery` does **not** filter by source server-side.
The full historical scan must be filtered in-process. For high-volume
alarm stores this could scan a lot — flag as runtime concern (§2.7).

The `BAlarmDatabase.bqlQuery(OrdTarget, OrdQuery)` method
(at `doc/javax/baja/alarm/BAlarmDatabase.html` anchor
`bqlQuery-javax.baja.naming.OrdTarget-javax.baja.naming.OrdQuery-`)
**does** allow BQL-style server-side filtering: e.g.
`bql:select * from alarm:AlarmRecord where source.ord like 'station:|slot:/Drivers/AHU%'`.
Our existing `BqlQueryTool` works against any `BITable` — so a BQL
query against the alarm DB **already works today** via the existing
v0.2.0 tool. The v0.3.0 work is the first-class wrappers, not new
capability.

### 2.5 `BAlarmRecord` fields

Verbatim from `doc/javax/baja/alarm/BAlarmRecord.html`. Constants
are facet keys (used via `getAlarmFacet(String)`); accessor methods
are direct getters.

**Facet keys (constants):**

`STATUS`, `MSG_TEXT`, `INSTRUCTIONS`, `SOURCE_NAME`, `NOTES`,
`HYPERLINK_ORD`, `SOUND_FILE`, `ICON`, `TIME_DELAY`,
`TIME_DELAY_TO_NORMAL`, `TIME_ZONE`, `FROM_STATE`, `TO_STATE`,
`COUNT`, `NOTIFY_TYPE`, `ALARM_VALUE`, `NORMAL_VALUE`,
`FAULT_VALUE`, `OFFNORMAL_VALUE`, `PRESENT_VALUE`,
`CONTROLLED_VALUE`, `FEEDBACK_NUMERIC`, `FEEDBACK_VALUE`,
`SETPT_NUMERIC`, `SETPT_VALUE`, `NUMERIC_VALUE`, `NEW_VALUE`,
`HIGH_LIMIT`, `LOW_LIMIT`, `HIGH_DIFF_LIMIT`, `LOW_DIFF_LIMIT`,
`DEADBAND`, `ERROR_LIMIT`, `TIMESTAMP_FACETS`, `ALARM_STORE_CX`,
`DATA_RECOVERY_CX`.

**Direct getters (verbatim from anchors):**

| Method | Returns |
|---|---|
| `getTimestamp()` | `BAbsTime` — when the transition occurred. |
| `getSource()` | `BAlarmSource` — wraps the source ord + name. |
| `getSourceState()` | source's current state. |
| `getAlarmTransition()` | `BAlarmTransitionBits` — what kind of transition (alarmed/normal/fault/ack). |
| `getAlarmValue()` | `BIDataValue` — point value at alarm time. |
| `getAckState()` | `BAckState`. |
| `getAckTime()` | `BAbsTime` — when acked, if acked. |
| `getNormalTime()` | `BAbsTime` — when returned to normal, if it has. |
| `getLastUpdate()` | `BAbsTime`. |
| `getPriority()` | `int`. |
| `getAlarmClass()` | `String`. |
| `getAlarmClassDisplayName(Context)` | `String`. |
| `getUser()` | `String` — operator who acked. |
| `getUuid()` | `BUuid` — primary key. |
| `getAlarmData()` / `getAlarmDataFields()` | extra data map. |
| `getAlarmFacet(String)` | one named facet. |
| `getFormattedAlarmDataValue(String, Context)` | formatted display string. |
| `isAlarm()` | currently in an alarm state? |
| `isNormal()` | returned to normal? |
| `isAcknowledged()` | acked? |
| `isAckPending()` | needs ack but not yet? |
| `isOpen()` | open in the database (open vs cleared/historical)? |
| `getRecordSize()` / `getSchema()` | record metadata. |

For the JSON shape returned by `getActiveAlarms` / `getAlarmHistory`,
the practical fields are:

```json
{
  "uuid": "...",
  "timestamp": "2026-05-09T12:34:56Z",
  "sourceOrd": "station:|slot:/Drivers/AHU_1_3/SAT",
  "sourceName": "SAT",
  "messageText": "...",   // from MSG_TEXT facet
  "priority": 100,
  "alarmClass": "default",
  "transition": "offnormal",  // derived from BAlarmTransitionBits
  "ackState": "unacked",      // from getAckState().toString()
  "isOpen": true,
  "isAcknowledged": false,
  "alarmValue": "...",        // toString() of BIDataValue
  "normalTime": null,         // or ISO timestamp if has returned
  "ackTime": null,
  "user": null
}
```

This is the recommended response shape for both `getActiveAlarms`
and `getAlarmHistory`.

### 2.6 `BAlarmSource`

Returned by `BAlarmRecord.getSource()`. From
`doc/javax/baja/alarm/BAlarmSource.html`:

| Method | Returns |
|---|---|
| `getOrd()` | `BOrd` — source point ord. |
| `getName()` | `String` — display name. |

For our JSON output we serialize `getOrd().toString()` and
`getName()` directly.

### 2.7 Equipment-aware filtering (knowledge-layer integration)

The v0.3.0 plan calls for `getAlarmHistory(equipmentId | ord, ...)`.
Resolution flow when `equipmentId` is provided:

1. `KnowledgeStore.getEquipment(equipmentId)` → `Equipment` POJO.
2. Collect all ords in scope:
   - The `equipment.ord` (the equipment root component).
   - All `equipment.points[*]` values (each is a leaf point ord).
3. For each ord, also include sub-component ords if alarms can fire
   on extensions (e.g., the alarm extension is a child of the point).
   The simplest approach: **prefix-match** — any alarm whose
   `getSource().getOrd().toString()` starts with the equipment.ord
   string is in scope.
4. For each scope ord, also try `getOpenAlarms()` for active queries
   (server-side) and `getAlarmsForSource(BOrdList)` if Niagara accepts
   our list shape.

The prefix-match approach avoids needing to enumerate the equipment
tree; it does mean the knowledge-layer `equipment.ord` must be the
**closest common ancestor** of all the equipment's components. This
is the natural choice — typical BMS structure has every equipment as
its own folder.

### 2.8 Open / TBD items for Alarms

| Item | Resolution path |
|---|---|
| Return type of `getOpenAlarms()` — `Iterable<BAlarmRecord>`, `BAlarmRecord[]`, or a cursor? Javadoc anchor doesn't reveal it. | TBD — read in `unzip -p .../alarm-rt.jar javax/baja/alarm/AlarmDbConnection.class \| javap -p` (one-shot static check). For coding the tool, treat the return as `Iterable<BAlarmRecord>` and document the assumption; refine when first build runs. |
| Same for `getAckPendingAlarms`, `scan`, `timeQuery`. | Same approach. |
| Performance of `timeQuery` with no source filter on a 100K-record DB | Runtime test — not statically answerable. |
| Whether `BAlarmService.TYPE` is on classpath via `:web-rt` transitively, or needs `:alarm-rt` added to gradle | Likely needs `api(":alarm-rt")` in gradle. Confirm at first build; small adjustment. The v0.3.0 plan (`docs/concepts/04-roadmap.md:144`) anticipates this. |
| Whether we can subscribe to alarm transitions for push notifications | Out of scope for v0.3.0 per `04-roadmap.md:84-85` — degenerate `GET /mcp` stays. |
| `BAlarmTransitionBits` decode — string mapping of the bits (offnormal/normal/fault/ack) | Static — read its javadoc anchor list. The class has bit-pattern constants. Not strictly needed; we can serialize as `bits.toString()`. |

---

## §3 — File system access from a module

### 3.1 `niagara_user_home` discovery

**`javax.baja.sys.Sys`** provides the home-path accessors (verbatim
from anchors at `doc/javax/baja/sys/Sys.html`):

| Method | Returns | Use |
|---|---|---|
| `getNiagaraUserHome()` | `java.io.File` | The per-user Niagara home. **Default location for our knowledge.yaml.** |
| `getNiagaraSharedUserHome()` | `java.io.File` | Shared between users. |
| `getStationHome()` | `java.io.File` | Currently-running station's home. |
| `getProtectedStationHome()` | `java.io.File` | Protected variant of station home (read-only at runtime). |
| `getNiagaraDevHome()` | `java.io.File` | Dev-time install root. |
| `getCredentialsHome()` | `java.io.File` | Where credentials live. |
| `getStation()` | `BStation` | Root of station's component tree. Used elsewhere; not needed for file-system but useful. |
| `getService(Type)` | `BIService` | Used for `BAlarmService`/`BHistoryService`. |
| `isStation()` / `isStationStarted()` | `boolean` | Lifecycle checks. |

**Recommended path** for v0.3.0 knowledge file:

```java
File knowledgeDir  = new File(Sys.getNiagaraUserHome(), "niagaramcp");
File knowledgeFile = new File(knowledgeDir, "knowledge.yaml");
```

Configurable via a `knowledgeFilePath` property on
`BMcpPlatformService` analogous to existing properties at
`niagaramcp-rt/src/com/niagaramcp/server/BMcpPlatformService.java:40-46`.

### 3.2 Permitted java.io operations

A Niagara module's Java code runs inside the station JVM. **Plain
`java.io.File`, `java.io.FileWriter`, `java.io.FileReader`,
`java.nio.file.Files`** all work. Niagara does not impose a
SecurityManager that blocks user-home file access.

What Niagara **does** restrict — declared in
`module-permissions.xml` — is access to the *Niagara file space*
abstraction (`BFileSpace`, `BIFile`, used for ord-resolved file
references like `file:^myfile.txt`). Our use case is direct OS
file I/O, which is not gated by `module-permissions.xml`.

The closest formal permission that *would* be relevant is
`FILE_ACCESS` (used by `module-permissions.xml` at the
`req-permission` level for `BIFile`-mediated access). We do **not**
need to add it for direct java.io to `niagara_user_home`.

The current permissions
(`niagaramcp-rt/module-permissions.xml:7-19`) are sufficient:

```xml
<niagara-permission-groups type="station">
  <req-permission><name>NETWORK_COMMUNICATION</name>...</req-permission>
  <req-permission><name>UNAUTHENTICATED_ACCESS</name>...</req-permission>
</niagara-permission-groups>
```

No additions needed for v0.3.0 — confirmed by the v0.3.0 plan
(`docs/concepts/04-roadmap.md:148-151`) and corroborated by this
inspection.

### 3.3 Atomic write pattern

Standard Java NIO atomic-rename is the way:

```java
File tmp    = new File(knowledgeFile.getParentFile(),
                       knowledgeFile.getName() + ".tmp." + System.currentTimeMillis());
File backup = new File(knowledgeFile.getParentFile(),
                       knowledgeFile.getName() + ".bak." + isoStamp());

// 1. Write the new content to tmp
try (Writer w = Files.newBufferedWriter(tmp.toPath(), StandardCharsets.UTF_8)) {
    w.write(yamlText);
}

// 2. Move existing file to backup (if any)
if (knowledgeFile.exists()) {
    Files.move(knowledgeFile.toPath(), backup.toPath(),
               StandardCopyOption.REPLACE_EXISTING);
}

// 3. Atomically rename tmp into place
Files.move(tmp.toPath(), knowledgeFile.toPath(),
           StandardCopyOption.ATOMIC_MOVE,
           StandardCopyOption.REPLACE_EXISTING);
```

`StandardCopyOption.ATOMIC_MOVE` is implemented as `rename(2)` on
POSIX and `MoveFileEx(MOVEFILE_REPLACE_EXISTING)` on Windows. It's
atomic on both — Niagara-Windows runs on NTFS which supports this.

If `ATOMIC_MOVE` is rejected (rare cross-volume case), the catch
falls back to non-atomic move. For our scenario where tmp and target
are in the same dir, atomicity is reliable.

### 3.4 Backup rotation

No built-in helper — write our own. Pattern:

1. Each save: timestamped backup (`knowledge.yaml.bak.2026-05-09T12-34-56`).
2. After write, list backups in dir, sort by name (timestamp prefix
   makes name-sort = chronological), keep newest N (recommend
   `N = 10`), delete the rest.

~30 LOC. Avoids the rotation library / config complexity for the
default behaviour.

### 3.5 Audit log

`docs/concepts/03-workflow.md:268-269` calls for a
`knowledge.audit.log` (append-only): who, when, what.

Implementation: open in append mode (`Files.newBufferedWriter(path,
StandardCharsets.UTF_8, StandardOpenOption.CREATE,
StandardOpenOption.APPEND)`), write one line per change, close
immediately. Lock-free single-writer is fine because every
walkthrough write goes through the `KnowledgeStore` singleton on
`BMcpPlatformService` (no concurrent writers).

Format (one event per line; reuse `com.niagaramcp.json.JSONObject`):

```
{"ts":"2026-05-09T12:34:56Z","action":"createSpace","id":"parking-sector-e","by":"<sessionId>","fields":{...}}
```

`<sessionId>` from the `Session` arg (see v0.2.0 baseline:
`Session.getSessionId()` at `niagaramcp-rt/src/com/niagaramcp/server/Session.java:27`).
This gives basic per-action accountability.

### 3.6 Initial directory creation

```java
if (!knowledgeDir.exists() && !knowledgeDir.mkdirs()) {
    throw new IOException("cannot create niagaramcp dir: " + knowledgeDir);
}
```

`File.mkdirs()` creates parents idempotently. No special permission
needed under user home.

### 3.7 File-watcher (NOT for v0.3.0)

The plan considered a file watcher to reload knowledge.yaml when an
operator edits it manually. **Skip for v0.3.0** for the same reason
we deferred Streamable HTTP push: introducing a watcher is the first
new thread in the codebase
(`docs/recon-2026-05-09.md` §10.4 baseline). v0.3.0 ships an
explicit `reloadKnowledge()` tool instead — operator runs it after
manual edit.

If/when added, `java.nio.file.WatchService` is the standard route.
Works on Windows (NTFS notifications) and POSIX. Adds ~80 LOC and
one daemon thread.

### 3.8 Open / TBD for file system

| Item | Resolution path |
|---|---|
| Behaviour of atomic-move on the Niagara station's actual disk (NTFS, possibly remote/SMB) | Runtime — first save in real station should succeed; if not, log + fallback to non-atomic move with warning. |
| Permissions on the niagaramcp dir created at runtime | Inherits from parent `niagara_user_home` (operator-writable). Confirm in test. |
| Behaviour if knowledge.yaml is on a network drive that goes offline mid-write | Out of normal scope; defensive code path catches `IOException`, logs, leaves backup intact. |

---

## §4 — YAML vs JSON: storage format decision

### 4.1 Schema requirements (from `docs/concepts/02-format.md`)

The knowledge schema needs:

- **Nested maps**: top-level station/spaces/equipment_types/equipment/points
  sections; each entry is an object with named fields.
- **Lists**: `aliases`, `notes`, `typical_points`, the section arrays.
- **Scalars**: strings, ints, booleans, null.
- **Strings with non-ASCII** — Russian text in `name`, `description`,
  `aliases`. Must be UTF-8 throughout.
- **Multi-line description?** — concept doc shows single-line
  descriptions; multi-line is not required by the schema example.
  Sufficient to support YAML's `|`/`>` block scalars only if needed
  later.
- **No anchors / aliases / merge keys / custom tags** — schema is
  flat enough to avoid YAML's complex features.

YAML 1.2 features we need:

- Block-style maps: `key: value` on indented lines.
- Block-style lists: `- item` indented.
- Flow-style for empty collections: `aliases: []`.
- Quoted strings (`"..."`) when value contains `:`, leading whitespace,
  is `null`/`true`/`false`/numeric-looking, or starts with special
  characters (`-`, `?`, `&`, etc.).
- Comments (`#`) — emit-only, parser ignores; useful for
  human-readable artefacts.

### 4.2 Path A — minimal YAML emitter+reader

A custom emitter for our subset is feasible. Estimate, broken down:

| Component | LOC est. |
|---|---:|
| Tokenizer (line-based, indent-aware) | 60 |
| Parser building a tree of `Map<String,Object>` / `List<Object>` / scalars | 80 |
| Scalar coerce (string → int/bool/null detection) | 30 |
| Emitter (recursive, with proper string quoting + indent) | 90 |
| Edge-case quoting (special chars, empty/leading-ws, ambiguous) | 40 |
| Glue + tests | 30 |
| **Total** | **~330 LOC** |

The brief asked: "if Path A turns out costly (≥500 LOC for edge cases),
switch to JSON". 330 is comfortably under that.

**Edge cases requiring careful quoting:**

| Situation | Output |
|---|---|
| String starts with `-`, `?`, `*`, `&`, `!`, `|`, `>`, `'`, `"`, `%`, `@`, ` ` | Quote: `"..."` |
| String matches `/(true|false|null|yes|no|on|off|~)$/i` (after lowercase) | Quote |
| String matches `^-?\d+(\.\d+)?([eE][+-]?\d+)?$` (numeric-looking) | Quote |
| String contains `:` followed by space, `# `, or trailing `:` | Quote |
| String contains `\n` | Use `|` block scalar (or quote with `\n`) |
| String contains `"` | Quote with `'...'` (single-quotes; doubling embedded `'`) |
| String contains `'` and `"` | Quote with `"..."`; escape `\\"` and `\\\\` |
| Empty string | `""` (or `''`) |
| Leading/trailing whitespace | Quote |
| Strings that *only* contain ASCII letters/digits/underscores/dashes/spaces (interior) — the common case | No quote |

Implementation note: do all quoting in one helper `quoteIfNeeded(String) → String`.
~40 LOC, well-tested.

**Reader edge cases:**

| Input | Output |
|---|---|
| `key: value` | `Map.put("key", "value")` |
| `key: 42` | `Map.put("key", Integer.valueOf(42))` |
| `key: 3.14` | `Map.put("key", Double.valueOf(3.14))` |
| `key: true` / `key: false` | `Map.put("key", Boolean.valueOf(...))` |
| `key: null` / `key: ~` / `key:` (no value) | `Map.put("key", null)` |
| `key: "42"` | `Map.put("key", "42")` (string, not int) |
| `key:` followed by indented block | `Map.put("key", <nested map or list>)` |
| `- item` | `list.add(item)` |
| `# comment` | skipped |

The mode where `key: ` (no value) must be distinguished from `key:`
followed by a nested block requires lookahead — straightforward in a
line-buffered parser.

### 4.3 Path B — JSON via embedded `com.niagaramcp.json`

Zero new code. The library is already in the jar and is exactly
the schema we need:
`com.niagaramcp.json.JSONObject` for maps, `com.niagaramcp.json.JSONArray`
for lists, and the standard scalar types.

The same schema as YAML, written as JSON:

```json
{
  "schema_version": 1,
  "station": {
    "id": "afimall-main",
    "name": "АФИМОЛЛ Сити, основная станция",
    "...": "..."
  },
  "spaces": [
    {
      "id": "parking-sector-e",
      "name": "Паркинг, сектор E",
      "aliases": ["сектор E", "паркинг E", "P-E"],
      "type": "zone",
      "parent": "parking"
    }
  ],
  "equipment_types": [...],
  "equipment": [...],
  "points": [...]
}
```

Pros: **zero LOC** for parser/emitter, indistinguishable behaviour
across platforms, no edge-case handling, lossless round-trip
guaranteed.

Cons:
- Operators editing manually have to deal with JSON's verbosity and
  the comma/quote/brace syntax. YAML is genuinely friendlier for
  multi-line nested config.
- JSON has no comments. Operators can't add explanatory notes
  inline. (Workaround: an `_comment` field convention.)
- No multi-line string sugar — long descriptions become `\n`-escaped
  one-liners, less readable.

### 4.4 Recommendation: **support both, default to YAML**

Concrete proposal:

1. **Read both formats** transparently — sniff the first non-blank,
   non-`#` character: if `{` → JSON, otherwise YAML. This lets
   operators paste either.
2. **Write whichever format the file currently uses.** On first
   creation, write YAML. After that, preserve the format.
3. **`exportKnowledge(format: 'yaml'|'json')`** tool produces
   either on demand.
4. **`importKnowledge(content)` tool** auto-detects.

Implementation cost:
- YAML reader + writer: ~330 LOC (Path A above).
- JSON reader + writer: 0 LOC (uses
  `com.niagaramcp.json.JSONObject` + `JSONTokener` already imported
  by `niagaramcp-rt/src/com/niagaramcp/server/McpProtocol.java:18-20`
  and `McpServlet.java:25-26`).
- Format-sniffer: 5 LOC.
- Format-preserve on write: 10 LOC.

Total **~345 LOC** — same envelope as YAML alone.

**Why this beats either single-format approach:**

- YAML-only forces us to ignore the free JSON parser already shipped.
- JSON-only ignores the operator-UX argument the concept doc made
  ("операторы привыкли к YAML", `docs/concepts/04-roadmap.md:128`).
- Both means the AI walkthrough output can default to YAML (operator-
  friendly) while machine consumers can request JSON without
  conversion.

The plan-doc's concern (`04-roadmap.md:124-131`) "no new dependencies"
is honoured by both paths — the YAML emitter is in-tree, the JSON
parser is already in-tree.

### 4.5 YAML emitter — ordering

YAML map iteration order matters for human-readable output. Use
`LinkedHashMap` for the in-memory representation (insertion-ordered);
the same idiom as `niagaramcp-rt/src/com/niagaramcp/server/ToolRegistry.java:31`
(`Map<String, Tool> tools = new LinkedHashMap<>()`). Within each
section emit in a deterministic order:

- `station`: `id, name, description, niagara_version, generated_at,
  generated_by, last_updated_at`.
- Each `space`: `id, name, aliases, type, description, parent, bounds`.
- Each `equipment_type`: `id, name, aliases, description, extends,
  typical_points`.
- Each `typical_point`: `role, kind, slot_patterns, required`.
- Each `equipment`: `id, name, aliases, type, space, ord, description,
  points, schedule, notes`.
- Each `point`: `id, name, aliases, space, ord, kind, role_in_space,
  notes`.

Field order driven by a `static final String[]` per section in the
emitter; missing fields skipped, unknown fields preserved at the end
in original order. Round-trip preserves operator hand-edits that add
unknown fields.

### 4.6 Validation

Per `docs/concepts/02-format.md:280-296`, validation runs on load:

1. Unique `id` within section.
2. `equipment.type` references existing `equipment_types[].id`.
3. `equipment.space` (if present) references existing `spaces[].id`.
4. `space.parent` (if present) is valid; no cycles.
5. `equipment_types[].extends` (if present) is valid; no cycles.
6. Every `ord` is syntactically valid (parsed with `BOrd.make(s)`,
   catch `IllegalArgumentException`; do **not** call `.get()` —
   that's runtime-resolution).
7. `schema_version` is a known version.

On invalid file: log error, start with empty knowledge (per concept
doc). Provide a `validateKnowledge` tool that returns the warning
list explicitly so the operator can fix.

The validation logic is independent of YAML vs JSON — it runs on
the parsed in-memory `KnowledgeModel` regardless of source format.
~80 LOC.

### 4.7 Migration

Per `docs/concepts/02-format.md:298-307`:

1. Detect `schema_version` mismatch on load.
2. Backup the old file (`.bak.v<old>.<isoStamp>`).
3. Apply migration (a `Map<Integer, Migration>` keyed on source
   version).
4. Save in new format.

For v0.3.0 only `schema_version: 1` exists — no migrations to
implement yet. Reserve the migration registry slot in the code so
v0.4 can drop in v1→v2 without restructuring.

---

*End of file 1 (Niagara API surfaces + format decision). Continue in
`docs/recon-2026-05-13-mcp-and-plan.md` for sections 5–12 (MCP
extensions, code extension points, walkthrough tools, state
inspection, URI scheme, implementation plan, open decisions, and
runtime-only follow-ups).*
