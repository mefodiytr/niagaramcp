#!/usr/bin/env python3
"""
niagaramcp smoke test — v0.2.0 + v0.3.0 coverage.

Standalone script — stdlib only, no pip install required.
Verifies:
  - Streamable HTTP transport (initialize, tools/list, tools/call, DELETE)
  - SSE backward compatibility (GET /sse + POST /messages)
  - Auth (401 without Bearer, 404 for garbage session id)
  - v0.3.0 capabilities: resources/list, resources/read,
    prompts/list, tools/call getKnowledgeSummary

Usage (PowerShell or any shell):
    py niagaramcp_smoke.py --host=192.168.1.10 --port=86 --scheme=http --token=YOUR_BEARER

Optional:
    --module=niagaramcp  module URL prefix (default niagaramcp)
    --insecure           skip TLS cert verification (for self-signed dev cert)
    --skip-sse           skip backward compat SSE tests
    --skip-v030          skip v0.3.0-specific tests (use for v0.2.0 stations)
"""
import argparse
import json
import ssl
import sys
import urllib.error
import urllib.request


# ─── ANSI ─────────────────────────────────────────────────────────────────────
GREEN  = "\033[92m"
RED    = "\033[91m"
YELLOW = "\033[93m"
GREY   = "\033[90m"
BOLD   = "\033[1m"
RESET  = "\033[0m"

BASELINE_TOOLS = {"echo", "listChildren", "readPoint", "writePoint", "bqlQuery"}


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
        if isinstance(raw, bytes):
            raw = raw.decode("utf-8")
        return json.loads(raw or "{}")
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
                           "clientInfo": {"name": "smoke", "version": "0.2"}}}
        return http_request(f"{self.base}/mcp", "POST",
                            self._hdrs(), body, insecure=self.insecure)

    def tools_list(self):
        body = {"jsonrpc": "2.0", "id": 2, "method": "tools/list"}
        return http_request(f"{self.base}/mcp", "POST",
                            self._hdrs(), body, insecure=self.insecure)

    def tools_call(self, name, args, request_id=3):
        body = {"jsonrpc": "2.0", "id": request_id, "method": "tools/call",
                "params": {"name": name, "arguments": args}}
        return http_request(f"{self.base}/mcp", "POST",
                            self._hdrs(), body, insecure=self.insecure)

    def resources_list(self):
        body = {"jsonrpc": "2.0", "id": 10, "method": "resources/list"}
        return http_request(f"{self.base}/mcp", "POST",
                            self._hdrs(), body, insecure=self.insecure)

    def resources_read(self, uri):
        body = {"jsonrpc": "2.0", "id": 11, "method": "resources/read",
                "params": {"uri": uri}}
        return http_request(f"{self.base}/mcp", "POST",
                            self._hdrs(), body, insecure=self.insecure)

    def prompts_list(self):
        body = {"jsonrpc": "2.0", "id": 13, "method": "prompts/list"}
        return http_request(f"{self.base}/mcp", "POST",
                            self._hdrs(), body, insecure=self.insecure)

    def delete(self):
        return http_request(f"{self.base}/mcp", "DELETE",
                            self._hdrs(), insecure=self.insecure)


# ─── SSE ──────────────────────────────────────────────────────────────────────
def sse_open(base, token, insecure=False, timeout=10):
    """Open SSE stream, read first 'event: endpoint' frame."""
    req = urllib.request.Request(f"{base.rstrip('/')}/sse", method="GET")
    req.add_header("Authorization", f"Bearer {token}")
    ctx = ssl._create_unverified_context() if insecure else None
    resp = urllib.request.urlopen(req, timeout=timeout, context=ctx)
    if resp.status != 200:
        return None, resp.status
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
        return p, f

    step(2, "POST /mcp tools/list (verify baseline tools present)")
    status, _, body = client.tools_list()
    if status == 200:
        j = parse_json(body)
        tools = j.get("result", {}).get("tools", []) if j else []
        names = {t.get("name") for t in tools}
        missing = BASELINE_TOOLS - names
        if not missing:
            ok(f"all 5 baseline tools present (total {len(tools)} tools)")
            p += 1
        else:
            fail(f"baseline tools missing: {missing}")
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
    """Returns (passed, failed)."""
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
                       "clientInfo": {"name": "smoke-sse", "version": "0.2"}}}
    status, _, _ = sse_messages_post(base, token, sid, body, insecure=insecure)
    if status == 202:
        ok(f"POST /messages?sessionId=... → HTTP 202")
        p += 1
    else:
        fail(f"POST /messages expected 202, got {status}")
        f += 1

    info("(skip reading SSE response body — 202 confirms enqueue path)")
    try:
        resp.close()
    except Exception:
        pass

    return p, f


def run_v030_tests(client):
    """v0.3.0-specific capabilities."""
    p, f = 0, 0

    step(9, "Fresh initialize for v0.3.0 tests")
    client.session_id = None
    status, headers, body = client.initialize()
    if status == 200:
        sid = headers.get("Mcp-Session-Id") or headers.get("mcp-session-id")
        client.session_id = sid
        ok(f"new session: {sid[:12]}...")
        p += 1
    else:
        fail(f"HTTP {status}")
        f += 1
        return p, f

    step(10, "POST /mcp resources/list (expect non-empty array)")
    status, _, body = client.resources_list()
    if status == 200:
        j = parse_json(body)
        resources = j.get("result", {}).get("resources", []) if j else []
        if resources:
            uris = [r.get("uri") for r in resources]
            ok(f"got {len(resources)} resources: {uris}")
            p += 1
        else:
            fail(f"resources array empty or missing: {body[:200]!r}")
            f += 1
    else:
        fail(f"HTTP {status}: {body[:200]!r}")
        f += 1

    step(11, "POST /mcp resources/read niagara://overview")
    status, _, body = client.resources_read("niagara://overview")
    if status == 200:
        j = parse_json(body)
        contents = j.get("result", {}).get("contents", []) if j else []
        if contents and (contents[0].get("text") or contents[0].get("blob")):
            text = contents[0].get("text", "")
            ok(f"overview content received ({len(text)} chars)")
            p += 1
        else:
            fail(f"contents missing or empty: {body[:200]!r}")
            f += 1
    else:
        fail(f"HTTP {status}: {body[:200]!r}")
        f += 1

    step(12, "POST /mcp prompts/list (expect 7 prompts)")
    status, _, body = client.prompts_list()
    if status == 200:
        j = parse_json(body)
        prompts = j.get("result", {}).get("prompts", []) if j else []
        names = [p_.get("name") for p_ in prompts]
        if len(prompts) >= 7:
            ok(f"got {len(prompts)} prompts: {names}")
            p += 1
        elif len(prompts) > 0:
            fail(f"expected ≥7 prompts, got {len(prompts)}: {names}")
            f += 1
        else:
            fail(f"prompts array empty: {body[:200]!r}")
            f += 1
    else:
        fail(f"HTTP {status}: {body[:200]!r}")
        f += 1

    step(13, "POST /mcp tools/call getKnowledgeSummary")
    status, _, body = client.tools_call("getKnowledgeSummary", {},
                                         request_id=14)
    if status == 200:
        j = parse_json(body)
        content = j.get("result", {}).get("content", []) if j else []
        text = content[0].get("text") if content else None
        if text:
            ok(f"summary returned ({len(text)} chars): {text[:120]!r}")
            p += 1
        else:
            fail(f"empty content: {body[:200]!r}")
            f += 1
    else:
        fail(f"HTTP {status}: {body[:200]!r}")
        f += 1

    client.delete()
    return p, f


# ─── v0.3.1 diagnostics + /health ─────────────────────────────────────────────
def run_v031_tests(client, base, insecure=False):
    """v0.3.1-specific diagnostic tools + unauthenticated /health."""
    p, f = 0, 0

    step(14, "Fresh initialize for v0.3.1 tests")
    client.session_id = None
    status, headers, body = client.initialize()
    if status == 200:
        sid = headers.get("Mcp-Session-Id") or headers.get("mcp-session-id")
        client.session_id = sid
        ok(f"new session: {sid[:12]}...")
        p += 1
    else:
        fail(f"HTTP {status}")
        f += 1
        return p, f

    step(15, "tools/call getServerInfo (verify version + knowledgeFile + transports)")
    status, _, body = client.tools_call("getServerInfo", {}, request_id=15)
    if status == 200:
        j = parse_json(body)
        content = j.get("result", {}).get("content", []) if j else []
        text = content[0].get("text") if content else None
        info = parse_json(text) if text else None
        if info and "version" in info and "knowledgeFile" in info \
                and "transports" in info and "tools" in info:
            ok(f"version={info.get('version')}, "
               f"tools={len(info.get('tools', []))}, "
               f"resources={len(info.get('resources', []))}, "
               f"prompts={len(info.get('prompts', []))}")
            p += 1
        else:
            fail(f"missing required keys in serverInfo: {text!r}")
            f += 1
    else:
        fail(f"HTTP {status}: {body[:200]!r}")
        f += 1

    step(16, "tools/call probeOrd with a known-valid ord (station root)")
    status, _, body = client.tools_call("probeOrd",
                                         {"ord": "station:|slot:/"},
                                         request_id=16)
    if status == 200:
        j = parse_json(body)
        content = j.get("result", {}).get("content", []) if j else []
        text = content[0].get("text") if content else None
        info = parse_json(text) if text else None
        if info and info.get("exists") is True:
            ok(f"station root resolved: type={info.get('type')}, "
               f"slotCount={info.get('slotCount')}")
            p += 1
        else:
            fail(f"expected exists=true for station root: {text!r}")
            f += 1
    else:
        fail(f"HTTP {status}: {body[:200]!r}")
        f += 1

    step(17, "tools/call probeOrd with garbage ord — expect {exists: false}, NOT error")
    status, _, body = client.tools_call("probeOrd",
                                         {"ord": "station:|slot:/__no_such_thing__"},
                                         request_id=17)
    if status == 200:
        j = parse_json(body)
        content = j.get("result", {}).get("content", []) if j else []
        is_error = j.get("result", {}).get("isError", False)
        text = content[0].get("text") if content else None
        info = parse_json(text) if text else None
        # garbage should yield exists=false, isError=false at MCP level
        if info and info.get("exists") is False and not is_error:
            ok(f"garbage ord correctly returned exists=false")
            p += 1
        else:
            fail(f"expected exists=false isError=false; got isError={is_error}, info={info!r}")
            f += 1
    else:
        fail(f"HTTP {status}: {body[:200]!r}")
        f += 1

    step(18, "tools/call checkKnowledgeIntegrity (verify response shape)")
    status, _, body = client.tools_call("checkKnowledgeIntegrity", {},
                                         request_id=18)
    if status == 200:
        j = parse_json(body)
        content = j.get("result", {}).get("content", []) if j else []
        text = content[0].get("text") if content else None
        info = parse_json(text) if text else None
        if info and "totalRefs" in info and "validRefs" in info \
                and "brokenRefs" in info:
            ok(f"integrity: total={info.get('totalRefs')}, "
               f"valid={info.get('validRefs')}, "
               f"broken={info.get('brokenCount', len(info.get('brokenRefs', [])))}")
            p += 1
        else:
            fail(f"missing required keys: {text!r}")
            f += 1
    else:
        fail(f"HTTP {status}: {body[:200]!r}")
        f += 1

    step(19, "GET /niagaramcp/health (no auth) — expect 200 + JSON")
    health_url = f"{base.rstrip('/')}/health"
    # Send WITHOUT Authorization header
    req = urllib.request.Request(health_url, method="GET")
    ctx = ssl._create_unverified_context() if insecure else None
    try:
        resp = urllib.request.urlopen(req, timeout=10, context=ctx)
        body = resp.read().decode("utf-8", errors="replace")
        status = resp.status
    except urllib.error.HTTPError as he:
        status = he.code
        body = he.read().decode("utf-8", errors="replace")
    except Exception as e:
        fail(f"GET /health failed: {e}")
        return p + 0, f + 1
    if status in (200, 503):
        j = parse_json(body)
        if j and "status" in j and "version" in j:
            ok(f"HTTP {status}, status={j.get('status')}, "
               f"version={j.get('version')}, "
               f"healthyServices={j.get('healthyServices')}")
            p += 1
        else:
            fail(f"missing status/version in body: {body[:200]!r}")
            f += 1
    else:
        fail(f"unexpected HTTP {status}: {body[:200]!r}")
        f += 1

    client.delete()
    return p, f


# ─── v0.4 operational additions ───────────────────────────────────────────────
def run_v04_tests(client):
    """v0.4-specific: tool category tags, getDiagnosticDump, transports list."""
    p, f = 0, 0

    step(20, "Fresh initialize for v0.4 tests + verify serverInfo.transports")
    client.session_id = None
    status, headers, body = client.initialize()
    if status == 200:
        sid = headers.get("Mcp-Session-Id") or headers.get("mcp-session-id")
        client.session_id = sid
        j = parse_json(body)
        info = j.get("result", {}).get("serverInfo", {}) if j else {}
        transports = info.get("transports")
        if isinstance(transports, list) and len(transports) > 0:
            ok(f"new session + transports={transports}")
            p += 1
        else:
            fail(f"missing or empty serverInfo.transports: {info!r}")
            f += 1
    else:
        fail(f"HTTP {status}")
        f += 1
        return p, f

    step(21, "tools/list: every tool has 'category' field; ≥6 distinct categories")
    status, _, body = client.tools_list()
    if status == 200:
        j = parse_json(body)
        tools = j.get("result", {}).get("tools", []) if j else []
        missing = [t.get("name") for t in tools if not t.get("category")]
        cats = sorted({t.get("category") for t in tools if t.get("category")})
        if not missing and len(cats) >= 6:
            ok(f"{len(tools)} tools across {len(cats)} categories: {cats}")
            p += 1
        elif missing:
            fail(f"tools without category: {missing}")
            f += 1
        else:
            fail(f"only {len(cats)} distinct categories: {cats}")
            f += 1
    else:
        fail(f"HTTP {status}: {body[:200]!r}")
        f += 1

    step(22, "tools/call getDiagnosticDump — verify top-level keys")
    status, _, body = client.tools_call("getDiagnosticDump", {}, request_id=22)
    if status == 200:
        j = parse_json(body)
        content = j.get("result", {}).get("content", []) if j else []
        text = content[0].get("text") if content else None
        info = parse_json(text) if text else None
        required_keys = {"server", "sessions", "knowledge", "health", "auditLogTail"}
        if info and required_keys.issubset(info.keys()):
            ok(f"diagnostic dump keys present: {sorted(info.keys())}")
            p += 1
        else:
            actual = sorted(info.keys()) if info else None
            fail(f"missing required keys; got {actual}")
            f += 1
    else:
        fail(f"HTTP {status}: {body[:200]!r}")
        f += 1

    client.delete()
    return p, f


# ─── main ─────────────────────────────────────────────────────────────────────
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", required=True)
    ap.add_argument("--port", type=int, default=4911)
    ap.add_argument("--token", required=True,
                    help="Bearer apiToken from BMcpPlatformService")
    ap.add_argument("--module", default="niagaramcp",
                    help="module URL prefix")
    ap.add_argument("--scheme", default="https", choices=["http", "https"])
    ap.add_argument("--insecure", action="store_true",
                    help="skip TLS cert verification")
    ap.add_argument("--skip-sse", action="store_true")
    ap.add_argument("--skip-v030", action="store_true",
                    help="skip v0.3.0 tests (resources, prompts, knowledge)")
    ap.add_argument("--skip-v031", action="store_true",
                    help="skip v0.3.1 tests (diagnostics + /health)")
    ap.add_argument("--skip-v04", action="store_true",
                    help="skip v0.4 tests (categories, dump, transports list)")
    args = ap.parse_args()

    base = f"{args.scheme}://{args.host}:{args.port}/{args.module}"
    print(f"{BOLD}niagaramcp smoke test (v0.2.0 + v0.3.0 + v0.3.1 + v0.4){RESET}")
    print(f"  base URL: {base}")
    print(f"  insecure: {args.insecure}")
    print(f"  skip SSE: {args.skip_sse}")
    print(f"  skip v0.3.0: {args.skip_v030}")
    print(f"  skip v0.3.1: {args.skip_v031}")
    print(f"  skip v0.4:   {args.skip_v04}")

    client = StreamableClient(base, args.token, insecure=args.insecure)

    p_s, f_s = run_streamable_tests(client)

    p_sse, f_sse = (0, 0)
    if not args.skip_sse:
        p_sse, f_sse = run_sse_compat_test(base, args.token,
                                            insecure=args.insecure)

    p_v3, f_v3 = (0, 0)
    if not args.skip_v030:
        p_v3, f_v3 = run_v030_tests(client)

    p_v31, f_v31 = (0, 0)
    if not args.skip_v031:
        p_v31, f_v31 = run_v031_tests(client, base, insecure=args.insecure)

    p_v4, f_v4 = (0, 0)
    if not args.skip_v04:
        p_v4, f_v4 = run_v04_tests(client)

    total_p = p_s + p_sse + p_v3 + p_v31 + p_v4
    total_f = f_s + f_sse + f_v3 + f_v31 + f_v4
    print(f"\n{BOLD}Result:{RESET} "
          f"{GREEN}{total_p} passed{RESET}, "
          f"{RED if total_f else GREY}{total_f} failed{RESET}")
    sys.exit(0 if total_f == 0 else 1)


if __name__ == "__main__":
    main()
