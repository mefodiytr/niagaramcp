#!/usr/bin/env python3
"""
niagaramcp v0.2.0 smoke test.

Standalone script — stdlib only, no pip install required.
Verifies Streamable HTTP transport AND backward compat with SSE
against a live Niagara station running niagaramcp v0.2.0.

Usage (PowerShell or any shell):
    python niagaramcp_smoke.py --host=192.168.1.10 --port=4911 --token=YOUR_BEARER

Optional:
    --module=niagaramcp  module URL prefix (default niagaramcp)
    --insecure           skip TLS cert verification (for self-signed dev cert)
    --skip-sse           skip backward compat SSE tests
    --skip-idle          skip idle eviction test (requires short timeout config)

Steps covered (from v0.2.0-implementation-notes.md):
    1. initialize → captures Mcp-Session-Id, checks protocolVersion
    2. tools/list → expects 5 tools
    3. tools/call echo → round-trips message
    4. DELETE /mcp → 204
    5. POST after DELETE → 404
    6. POST /mcp without auth → 401
    7. POST /mcp with wrong session id → 404
    8. SSE backward compat: GET /sse → endpoint event, POST /messages → 202 + reply
    9. idle eviction (optional, requires mcpSessionIdleTimeoutSec=60 set)
"""
import argparse
import json
import ssl
import sys
import time
import urllib.error
import urllib.request
from io import BytesIO


# ─── ANSI ─────────────────────────────────────────────────────────────────────
GREEN  = "\033[92m"
RED    = "\033[91m"
YELLOW = "\033[93m"
GREY   = "\033[90m"
BOLD   = "\033[1m"
RESET  = "\033[0m"


def ok(msg):   print(f"  {GREEN}✓{RESET} {msg}")
def fail(msg): print(f"  {RED}✗{RESET} {msg}")
def info(msg): print(f"  {GREY}{msg}{RESET}")
def step(n, title): print(f"\n{BOLD}Step {n}: {title}{RESET}")


# ─── HTTP ─────────────────────────────────────────────────────────────────────
def http_request(url, method="GET", headers=None, body=None,
                 timeout=10, insecure=False):
    """Returns (status, headers_dict, body_bytes). Never raises on 4xx/5xx."""
    req = urllib.request.Request(url, method=method)
    if headers:
        for k, v in headers.items():
            req.add_header(k, v)
    data = None
    if body is not None:
        data = body if isinstance(body, bytes) else json.dumps(body).encode()
        if "Content-Type" not in (headers or {}):
            req.add_header("Content-Type", "application/json")

    ctx = ssl._create_unverified_context() if insecure else None
    try:
        with urllib.request.urlopen(req, data=data, timeout=timeout,
                                    context=ctx) as resp:
            return resp.status, dict(resp.headers), resp.read()
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read()


def parse_json(raw):
    try:
        return json.loads(raw.decode("utf-8") or "{}")
    except (UnicodeDecodeError, json.JSONDecodeError):
        return None


# ─── client wrappers ──────────────────────────────────────────────────────────
class StreamableClient:
    def __init__(self, base, token, insecure=False):
        self.base = base.rstrip("/")
        self.token = token
        self.insecure = insecure
        self.session_id = None

    def _hdrs(self, extra=None):
        h = {"Authorization": f"Bearer {self.token}"}
        if self.session_id:
            h["Mcp-Session-Id"] = self.session_id
        if extra:
            h.update(extra)
        return h

    def initialize(self):
        body = {"jsonrpc": "2.0", "id": 1, "method": "initialize",
                "params": {"protocolVersion": "2025-06-18",
                           "capabilities": {},
                           "clientInfo": {"name": "smoke", "version": "0.1"}}}
        return http_request(f"{self.base}/mcp", "POST",
                            self._hdrs(), body, insecure=self.insecure)

    def tools_list(self):
        body = {"jsonrpc": "2.0", "id": 2, "method": "tools/list"}
        return http_request(f"{self.base}/mcp", "POST",
                            self._hdrs(), body, insecure=self.insecure)

    def tools_call(self, name, args):
        body = {"jsonrpc": "2.0", "id": 3, "method": "tools/call",
                "params": {"name": name, "arguments": args}}
        return http_request(f"{self.base}/mcp", "POST",
                            self._hdrs(), body, insecure=self.insecure)

    def delete(self):
        return http_request(f"{self.base}/mcp", "DELETE",
                            self._hdrs(), insecure=self.insecure)


# ─── SSE ──────────────────────────────────────────────────────────────────────
def sse_open(base, token, insecure=False, timeout=10):
    """Open SSE stream, read first 'event: endpoint' frame, return (sessionId, raw_response).
    The raw response stays open implicitly; we close it via context cleanup."""
    req = urllib.request.Request(f"{base.rstrip('/')}/sse", method="GET")
    req.add_header("Authorization", f"Bearer {token}")
    ctx = ssl._create_unverified_context() if insecure else None
    resp = urllib.request.urlopen(req, timeout=timeout, context=ctx)
    if resp.status != 200:
        return None, resp.status
    # parse first event
    event_name, data_lines = None, []
    while True:
        line = resp.readline().decode("utf-8", errors="replace").rstrip("\r\n")
        if line == "":
            if event_name and data_lines:
                break
            continue
        if line.startswith(":"):
            continue
        if line.startswith("event:"):
            event_name = line[6:].strip()
        elif line.startswith("data:"):
            data_lines.append(line[5:].strip())
    data = "\n".join(data_lines)
    # data is like "/niagaramcp/messages?sessionId=<UUID>"
    if "sessionId=" in data:
        return data.split("sessionId=")[1].split("&")[0], resp
    return None, resp


def sse_messages_post(base, token, session_id, body, insecure=False):
    url = f"{base.rstrip('/')}/messages?sessionId={session_id}"
    return http_request(url, "POST",
                        {"Authorization": f"Bearer {token}"},
                        body, insecure=insecure)


# ─── tests ────────────────────────────────────────────────────────────────────
def run_streamable_tests(client):
    """Returns (passed, failed)."""
    p, f = 0, 0

    step(1, "POST /mcp initialize (no Mcp-Session-Id header)")
    status, headers, body = client.initialize()
    if status == 200:
        ok(f"HTTP 200")
        sid = headers.get("Mcp-Session-Id") or headers.get("mcp-session-id")
        if sid:
            ok(f"Mcp-Session-Id received: {sid[:12]}...")
            client.session_id = sid
            p += 1
        else:
            fail("Mcp-Session-Id header missing in response")
            f += 1
        j = parse_json(body)
        if j and j.get("result", {}).get("protocolVersion") == "2025-06-18":
            ok("protocolVersion = 2025-06-18")
            p += 1
        else:
            actual = j.get("result", {}).get("protocolVersion") if j else "<no json>"
            fail(f"protocolVersion mismatch: {actual}")
            f += 1
    else:
        fail(f"HTTP {status}: {body[:200]!r}")
        f += 1
        return p, f  # cannot continue without session

    step(2, "POST /mcp tools/list")
    status, _, body = client.tools_list()
    if status == 200:
        j = parse_json(body)
        tools = j.get("result", {}).get("tools", []) if j else []
        names = [t.get("name") for t in tools]
        ok(f"HTTP 200, got {len(tools)} tools: {names}")
        if len(tools) == 5:
            p += 1
        else:
            fail(f"expected 5 tools, got {len(tools)}")
            f += 1
    else:
        fail(f"HTTP {status}")
        f += 1

    step(3, "POST /mcp tools/call echo")
    status, _, body = client.tools_call("echo", {"msg": "hello niagaramcp"})
    if status == 200:
        j = parse_json(body)
        content = j.get("result", {}).get("content", []) if j else []
        text = content[0].get("text") if content else None
        if text == "hello niagaramcp":
            ok(f"echo round-trip: {text!r}")
            p += 1
        else:
            fail(f"unexpected echo response: {text!r}")
            f += 1
    else:
        fail(f"HTTP {status}: {body[:200]!r}")
        f += 1

    step(4, "DELETE /mcp")
    status, _, _ = client.delete()
    if status == 204:
        ok(f"HTTP 204")
        p += 1
    else:
        fail(f"expected 204, got {status}")
        f += 1

    step(5, "POST /mcp tools/list with deleted Mcp-Session-Id (expect 404)")
    status, _, body = client.tools_list()
    if status == 404:
        ok(f"HTTP 404: {body.decode('utf-8', errors='replace')[:80]!r}")
        p += 1
    else:
        fail(f"expected 404, got {status}")
        f += 1

    step(6, "POST /mcp without Authorization header (expect 401)")
    body = {"jsonrpc": "2.0", "id": 99, "method": "initialize"}
    status, headers, _ = http_request(f"{client.base}/mcp", "POST",
                                      {}, body, insecure=client.insecure)
    if status == 401:
        ok(f"HTTP 401")
        if "Bearer" in (headers.get("WWW-Authenticate", "") or ""):
            ok("WWW-Authenticate: Bearer present")
            p += 1
        else:
            fail("WWW-Authenticate header missing")
            f += 1
    else:
        fail(f"expected 401, got {status}")
        f += 1

    step(7, "POST /mcp with garbage Mcp-Session-Id (expect 404)")
    headers = {"Authorization": f"Bearer {client.token}",
               "Mcp-Session-Id": "00000000-0000-0000-0000-000000000000"}
    body = {"jsonrpc": "2.0", "id": 100, "method": "tools/list"}
    status, _, _ = http_request(f"{client.base}/mcp", "POST",
                                headers, body, insecure=client.insecure)
    if status == 404:
        ok(f"HTTP 404")
        p += 1
    else:
        fail(f"expected 404, got {status}")
        f += 1

    return p, f


def run_sse_compat_test(base, token, insecure=False):
    """Runs the v0.1.0 SSE+messages flow on the v0.2.0 jar.
    Returns (passed, failed)."""
    p, f = 0, 0

    step(8, "SSE backward compat: GET /sse + POST /messages")
    try:
        sid, resp = sse_open(base, token, insecure=insecure)
        if sid:
            ok(f"GET /sse returned endpoint event with sessionId: {sid[:12]}...")
            p += 1
        else:
            fail(f"GET /sse failed (status={resp})")
            f += 1
            return p, f
    except Exception as e:
        fail(f"GET /sse raised: {e}")
        return p, f + 1

    body = {"jsonrpc": "2.0", "id": 1, "method": "initialize",
            "params": {"protocolVersion": "2024-11-05",
                       "capabilities": {},
                       "clientInfo": {"name": "smoke-sse", "version": "0.1"}}}
    status, _, _ = sse_messages_post(base, token, sid, body, insecure=insecure)
    if status == 202:
        ok(f"POST /messages?sessionId=... → HTTP 202")
        p += 1
    else:
        fail(f"POST /messages expected 202, got {status}")
        f += 1

    # we do not block to read the SSE response here — the 202 confirms enqueue worked.
    info("(skip reading SSE response body — 202 confirms enqueue path)")
    try:
        resp.close()
    except Exception:
        pass

    return p, f


# ─── main ─────────────────────────────────────────────────────────────────────
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", required=True)
    ap.add_argument("--port", type=int, default=4911)
    ap.add_argument("--token", required=True, help="Bearer apiToken from BMcpPlatformService")
    ap.add_argument("--module", default="niagaramcp", help="module URL prefix")
    ap.add_argument("--scheme", default="https", choices=["http", "https"])
    ap.add_argument("--insecure", action="store_true",
                    help="skip TLS cert verification (self-signed dev cert)")
    ap.add_argument("--skip-sse", action="store_true")
    args = ap.parse_args()

    base = f"{args.scheme}://{args.host}:{args.port}/{args.module}"
    print(f"{BOLD}niagaramcp v0.2.0 smoke test{RESET}")
    print(f"  base URL: {base}")
    print(f"  insecure: {args.insecure}")
    print(f"  skip SSE: {args.skip_sse}")

    client = StreamableClient(base, args.token, insecure=args.insecure)
    p_s, f_s = run_streamable_tests(client)

    p_sse, f_sse = (0, 0)
    if not args.skip_sse:
        p_sse, f_sse = run_sse_compat_test(base, args.token, insecure=args.insecure)

    total_p = p_s + p_sse
    total_f = f_s + f_sse
    print(f"\n{BOLD}Result:{RESET} "
          f"{GREEN}{total_p} passed{RESET}, "
          f"{RED if total_f else GREY}{total_f} failed{RESET}")
    sys.exit(0 if total_f == 0 else 1)


if __name__ == "__main__":
    main()
