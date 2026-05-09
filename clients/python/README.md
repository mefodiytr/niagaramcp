# Python smoke test client for niagaramcp

Standalone verification script — stdlib only, no `pip install` required.
Verifies that a deployed niagaramcp module is functioning correctly:
Streamable HTTP transport, SSE backward compatibility, and basic auth/error
behavior.

## Prerequisites

- Python 3.10+ (uses `urllib`, `ssl`, `argparse`, `json` — all stdlib)
- A running Niagara station with niagaramcp module deployed
- The station's `apiToken` set on `McpPlatformService` (Workbench →
  Config → Services → McpPlatformService → property `apiToken`).
  Generate one with `[guid]::NewGuid().ToString()` in PowerShell or any
  random string of sufficient length.
- The HTTP or HTTPS port from `Config → Services → WebService`.

## Usage

```powershell
# HTTP (simplest, no TLS issues):
py niagaramcp_smoke.py --host=<station-host> --port=<httpPort> --scheme=http --token=<apiToken>

# HTTPS with self-signed dev cert:
py niagaramcp_smoke.py --host=<station-host> --port=<httpsPort> --scheme=https --insecure --token=<apiToken>
```

Common flags:

| Flag | Default | Description |
|---|---|---|
| `--host` | (required) | Station hostname or IP |
| `--port` | 4911 | HTTP/HTTPS port (NOT Foxs port) |
| `--token` | (required) | apiToken value from McpPlatformService |
| `--scheme` | https | http or https |
| `--insecure` | off | Skip TLS cert verification (for dev cert) |
| `--module` | niagaramcp | URL prefix (= module name) |
| `--skip-sse` | off | Skip backward-compat SSE tests |

## Coverage

Runs 8 automated verification steps:

| # | Step | Expected |
|---|---|---|
| 1 | `POST /mcp` initialize without Mcp-Session-Id | 200, header `Mcp-Session-Id`, `protocolVersion=2025-06-18` |
| 2 | `POST /mcp` tools/list | 200, 5 tools |
| 3 | `POST /mcp` tools/call echo | 200, message round-trips |
| 4 | `DELETE /mcp` | 204 |
| 5 | `POST /mcp` tools/list with deleted Mcp-Session-Id | 404 |
| 6 | `POST /mcp` without Authorization | 401 + `WWW-Authenticate: Bearer` |
| 7 | `POST /mcp` with garbage Mcp-Session-Id | 404 |
| 8 | SSE backward compat: `GET /sse` + `POST /messages` | 200 endpoint event + 202 |

## Manual checks not covered

- **Idle eviction (verification step 5 in implementation notes)** — requires
  temporarily setting `mcpSessionIdleTimeoutSec=60` on McpPlatformService,
  starting a session, waiting 70+ seconds, then attempting a request with
  the same session id. Should return 404. Reset to 1800 after.
- **Concurrent connections (step 8)** — open 5 simultaneous shells running
  this script in parallel; all should complete without deadlock.

## Exit codes

- `0` — all passed
- `1` — at least one failed (details in stdout)

## Common issues

- `UnicodeEncodeError: 'latin-1' codec can't encode...` — non-ASCII chars
  in `--token`. Token must be ASCII (UUIDs are ideal).
- `Remote end closed connection without response` — port mismatch. 4911 is
  Niagara's Foxs (proprietary TLS), not HTTPS. Find HTTP/HTTPS port in
  `Config → Services → WebService`.
- HTTP 401 on Step 1 — `apiToken` in Workbench doesn't match `--token`.
- HTTP 404 on Step 1 (`Not Found: /mcp`) — module not loaded or running v0.1.0.
  Check Workbench → Spy → Module Manager.
- HTTP 503 — `BMcpPlatformService.enabled = false`. Enable it in Workbench.
- `SSL: CERTIFICATE_VERIFY_FAILED` — add `--insecure` flag.

## Future plans

This is a minimal smoke test. A more complete Python client (with proper
package structure, async support, full MCP protocol coverage, integration
test framework) will be developed alongside niagaramcp v0.3.0+.
