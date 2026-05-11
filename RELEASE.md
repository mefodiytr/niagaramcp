# Releasing niagaramcp

Repo root = this `niagaramcp/` directory. Run all commands from here:

```powershell
cd C:\Users\User\Niagara4.15\TridiumEMEA\MCP_Server\niagaramcp
```

Build root is the parent `TridiumEMEA/` (Niagara dev workspace) — that's
where `gradlew.bat` lives:

```powershell
cd ..\..    # -> TridiumEMEA\
.\gradlew.bat :niagaramcp-rt:clean :niagaramcp-rt:jar
```

---

## Cut-a-release checklist

1. **Bump the version** — `NIAGARAMCP_VERSION` in
   `niagaramcp-rt/src/com/niagaramcp/server/tools/GetServerInfoTool.java`
   (surfaced by `getServerInfo` / `/health` / `getFeatureDump`). The
   Niagara module-manifest version is a separate axis set in the build.
2. **Update `CHANGELOG.md`** — move `## [Unreleased]` content into a dated
   `## [X.Y.Z] — YYYY-MM-DD` section; leave a fresh empty `## [Unreleased]`.
3. **Docs** — `README.md` / `README.ru.md` version-tagged sections,
   `docs/API.md` (tool count, error codes), `docs/SESSIONS.md`.
4. **Clean build** — `.\gradlew.bat :niagaramcp-rt:clean :niagaramcp-rt:jar`
   from `TridiumEMEA/`. Confirm `niagaramcp-rt/build/libs/niagaramcp-rt.jar`
   exists. (The `:jar` task auto-installs into `$niagara_home/modules/`; if
   the station is running it fails on the file lock — stop the station and
   re-run, or copy by hand.)
5. **Smoke** — start the station, then
   `py clients/python/niagaramcp_smoke.py --host=<h> --port=<p> --scheme=http --token=<apiToken>`.
   It runs all version suites; expect all-green. (For the v0.5 user-Context
   steps the station needs `BMcpPlatformService.enableTestSetup=true` and a
   pre-created `mcpSmokeUser` with add-permission under the smoke parent ord;
   flip `enableTestSetup` back to `false` after.)
6. **Commit + tag** — on `main`:
   ```bash
   git add -A && git commit -m "release: vX.Y.Z"
   git tag -a vX.Y.Z -m "vX.Y.Z — <one-line summary>"
   ```
7. **Push** (this is the deploy trigger):
   ```bash
   git push origin main
   git push origin vX.Y.Z
   ```
   On the BCAi container: `git pull` → rebuild the Docker image. (First-time
   only: `git remote add origin <https://github.com/OWNER/REPO.git>`.)
8. **GitHub Release** — Releases → Draft a new release → pick the tag →
   title `vX.Y.Z — <summary>` → paste the changelog/release-notes block →
   optionally attach `niagaramcp-rt/build/libs/niagaramcp-rt.jar` as an
   asset. (Re-sign with your enterprise cert for production use.)

---

## What must NOT be committed

`.gitignore` covers it; verify with `git status --ignored | head` before a
commit:

- `niagaramcp-rt/build/`, `niagaramcp-rt/.gradle/`, any `*.jar`
  (the built jar ships as a release asset, not in git)
- `*.jks`, `*.p12`, `*.pfx`, `*.keystore`, `keystore.properties` (signing)
- `local/`, `my-niagara.gradle*`, `log*.txt`, `docs/agent-briefs/`

---

## v0.5.3 — release notes (paste into the GitHub Release)

```markdown
# niagaramcp v0.5.3 — user-Context write milestone

Adds the per-user permission/audit gateway and the M1 write-tool set, plus
write-tool polish and link conversion. Combines four increments that landed
together (v0.5 → v0.5.3). Protocol unchanged (MCP 2025-06-18); the legacy
SSE+messages transport is retained alongside Streamable HTTP.
`tools/list` 38 → 46. Validated 34/34 by the smoke client against a live
Niagara 4.15.3.28 station.

## Highlights

- **User-Context auth** — bearer tokens resolve to a Niagara `BUser` via a
  per-user salted-SHA-256 `mcp:tokenHash` tag (constant-time compare; salt =
  the read-only `BMcpPlatformService.tokenSalt`). Mutating Baja calls run
  under a `Context` impersonating that user — Niagara's own permission model
  applies — and every mutation is audited (JSON-lines file under
  `<userHome>/niagaramcp/` + Workbench audit history when the history module
  is present). The legacy `apiToken` stays a read-only service identity.
  New error codes `-32010 ERR_PERMISSION_DENIED`, `-32011 ERR_USER_NOT_FOUND`.
- **M1 write tools** (all under user-Context, all audited):
  `createComponent`, `removeComponent` (dryRun default + inbound-link
  safety), `setSlot`, `clearSlot`, `invokeAction`, `addExtension`
  (applicability pre-check → `-32015`), `linkSlots` (+ optional
  `converterType` → `BConversionLink`), `unlinkSlots` (`{linkOrd}` or
  `{sinkOrd, linkName}`), `commitStation`. New error codes `-32013 … -32016`.
- **Protocol** — `tools/list` exposes `annotations`
  (readOnly/destructive/idempotent/openWorldHint) and `requiresUserContext`;
  `tools/call` results auto-promote JSON text to `structuredContent`
  (MCP §5.4) and carry `result.errorCode` / `result.errorData` for
  tool-thrown structured errors.
- `tools.Ords` helper — write tools resolve ords against the running station
  (relative `slot:/...` works as well as `station:|slot:/...`) and emit
  fully-qualified result ords.
- `BValueCoercer` — shared JSON↔BSimple coercion for
  `setSlot`/`invokeAction`/`clearSlot`.
- **Docs** — new `docs/API.md` (single-page API reference: endpoints, auth,
  all 46 tools, error codes, config); `getFeatureDump` error-code table
  extended to `-32016`.

## Compatibility

- Tridium Niagara **4.15.3.x** (tested on 4.15.3.28).
- Stock `baja`, `control-rt`, `web-rt`, `history-rt`, `alarm-rt`, `bql-rt`.
- Java 8 to build. Module signed with the Niagara dev-cert — re-sign with
  your enterprise cert for production.
- Backward-compatible with v0.4.x: all prior tools/resources/prompts work
  unchanged; the legacy SSE+messages transport is retained.

## Security

`apiToken` is admin-equivalent (read/diagnostic surface). Per-user MCP
tokens scope writes to a `BUser`'s Niagara permissions and audit them, but
there is still no rate-limiting / token-rotation built in — don't expose to
untrusted networks. `setupTestUser` is gated by `enableTestSetup` (default
false); keep it false outside CI/smoke. See `docs/API.md` and `_CODE_REVIEW.md`.

## Install / upgrade

1. From `TridiumEMEA/`: `.\gradlew.bat :niagaramcp-rt:clean :niagaramcp-rt:jar`
2. Copy `niagaramcp-rt/build/libs/niagaramcp-rt.jar` to `$niagara_home/modules/`
   (stop the station first if it's running).
3. Restart the station. (Upgrades from v0.4.x are drop-in — no config change.)
4. New install: drag `McpPlatformService` from the `niagaramcp` palette into
   `Services/`, set `apiToken` to a fresh high-entropy value. For per-user
   write access, bind each user's `mcp:tokenHash` tag (see `docs/API.md`).

## Changelog

See [`CHANGELOG.md`](./CHANGELOG.md) — the `[0.5.3]` section and its
`v0.5 / v0.5.1 / v0.5.2 / v0.5.3` sub-sections.
```

---

## After pushing — quick GitHub sanity

- README renders (EN + RU abstract, the `docs/API.md` link works).
- `LICENSE` recognised (Apache-2.0).
- Repo description (one line): `MCP (Model Context Protocol) server for Tridium Niagara stations`.
- Tag `vX.Y.Z` shows under Releases.
- Topics (optional): `niagara`, `tridium`, `mcp`, `model-context-protocol`,
  `building-automation`, `iot`, `java`.
