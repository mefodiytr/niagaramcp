/*
 * Copyright 2026 niagaramcp contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.niagaramcp.server;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import javax.baja.alarm.BAlarmService;
import javax.baja.history.BHistoryService;
import javax.baja.sys.Sys;
import javax.baja.web.servlets.UnauthenticatedServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import com.niagaramcp.json.JSONArray;
import com.niagaramcp.json.JSONObject;
import com.niagaramcp.json.JSONTokener;
import com.niagaramcp.server.knowledge.KnowledgeStore;
import com.niagaramcp.server.tools.GetServerInfoTool;

/**
 * Niagara {@link UnauthenticatedServlet} that exposes
 * {@code GET /sse} (Server-Sent Events stream) and
 * {@code POST /messages?sessionId=…} (JSON-RPC 2.0 inbound) for
 * MCP-compatible clients. Authentication is performed in this class via
 * {@link #checkAuth(HttpServletRequest, HttpServletResponse)}, comparing
 * an {@code Authorization: Bearer …} header against the service's apiToken.
 */
public final class McpServlet extends UnauthenticatedServlet {

  private static final long serialVersionUID = 1L;

  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    String path = stripSlash(req.getPathInfo());
    // /health is the ONE exception to Bearer-on-every-endpoint: it's monitoring
    // infrastructure used by external probes (k8s, Prometheus, watchdog scripts).
    // Returns counts/status booleans only — no station data, no equipment names.
    // Health is also exempt from transport toggles — even when SSE+Streamable are
    // both off, health stays accessible so monitors can detect that state.
    if ("health".equals(path)) { handleHealth(req, resp); return; }
    if (!checkServiceEnabled(resp)) return;
    if (!checkAuth(req, resp)) return;
    if ("sse".equals(path)) {
      if (!checkTransportEnabled(resp, BMcpPlatformService.sseEnabled(), "sse")) return;
      handleSse(req, resp); return;
    }
    if ("mcp".equals(path)) {
      if (!checkTransportEnabled(resp, BMcpPlatformService.streamableEnabled(), "streamable-http")) return;
      handleStreamableGet(req, resp); return;
    }
    if ("knowledge.yaml".equals(path)) { handleKnowledgeGet(req, resp); return; }
    sendPlain(resp, 404, "Not Found: /" + path);
  }

  @Override
  protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    if (!checkServiceEnabled(resp)) return;
    if (!checkAuth(req, resp)) return;
    String path = stripSlash(req.getPathInfo());
    if ("messages".equals(path)) {
      if (!checkTransportEnabled(resp, BMcpPlatformService.sseEnabled(), "sse")) return;
      handleMessage(req, resp); return;
    }
    if ("mcp".equals(path)) {
      if (!checkTransportEnabled(resp, BMcpPlatformService.streamableEnabled(), "streamable-http")) return;
      handleStreamablePost(req, resp); return;
    }
    sendPlain(resp, 404, "Not Found: /" + path);
  }

  /**
   * PUT /knowledge.yaml — replace the whole knowledge document with the
   * raw YAML/JSON request body (no MCP session/JSON-RPC framing needed).
   * Same Bearer auth as every other endpoint except /health. Intended for
   * external agents/tools/scripts that want to read or replace the
   * knowledge document without implementing the MCP protocol itself.
   */
  @Override
  protected void doPut(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    if (!checkServiceEnabled(resp)) return;
    if (!checkAuth(req, resp)) return;
    String path = stripSlash(req.getPathInfo());
    if ("knowledge.yaml".equals(path)) { handleKnowledgePut(req, resp); return; }
    sendPlain(resp, 404, "Not Found: /" + path);
  }

  @Override
  protected void doDelete(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    if (!checkServiceEnabled(resp)) return;
    if (!checkAuth(req, resp)) return;
    String path = stripSlash(req.getPathInfo());
    if ("mcp".equals(path)) {
      if (!checkTransportEnabled(resp, BMcpPlatformService.streamableEnabled(), "streamable-http")) return;
      handleStreamableDelete(req, resp); return;
    }
    sendPlain(resp, 404, "Not Found: /" + path);
  }

  /**
   * Reject the request with HTTP 503 + JSON-RPC-style {@code -32009} body when
   * the targeted transport is disabled by an operator property.
   * @return {@code true} if enabled (caller proceeds); {@code false} if rejected
   *         (caller must return early).
   */
  private static boolean checkTransportEnabled(HttpServletResponse resp,
                                                boolean enabled,
                                                String transportName) throws IOException {
    if (enabled) return true;
    resp.setStatus(503);
    resp.setContentType("application/json; charset=utf-8");
    JSONObject err = new JSONObject();
    JSONObject errBody = new JSONObject();
    errBody.put("code", -32009);
    errBody.put("message", "Transport disabled: " + transportName);
    JSONObject errData = new JSONObject();
    errData.put("transport", transportName);
    errBody.put("data", errData);
    err.put("error", errBody);
    PrintWriter w = resp.getWriter();
    w.write(err.toString());
    w.flush();
    return false;
  }

  // ================================================================
  // Streamable HTTP handlers (MCP spec 2025-06-18)
  // ================================================================

  private static final String SESSION_ID_HEADER = "Mcp-Session-Id";

  /**
   * POST /mcp — JSON-RPC inbound for Streamable HTTP.
   *
   * <ul>
   *   <li>If the request lacks {@code Mcp-Session-Id}, the body must be an
   *       {@code initialize} call. The server generates a fresh session id,
   *       creates a {@link StreamableSession}, and returns the id in the
   *       response header.</li>
   *   <li>Otherwise the header must reference a live session (per
   *       {@link McpSessions#acquireStreamable(String, long)}); else 404.</li>
   *   <li>Successful dispatches return {@code 200 application/json}; client
   *       notifications (no JSON-RPC id) return {@code 202 Accepted} with an
   *       empty body. Streaming response shape (text/event-stream on POST)
   *       is intentionally not implemented in v0.2.0 — current tools produce
   *       no mid-call progress.</li>
   * </ul>
   */
  private void handleStreamablePost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    JSONObject request;
    try {
      request = readJson(req);
    } catch (Exception e) {
      sendPlain(resp, 400, "Bad JSON: " + e.getMessage());
      return;
    }

    String sessionIdHeader = req.getHeader(SESSION_ID_HEADER);
    StreamableSession session;
    if (sessionIdHeader == null || sessionIdHeader.length() == 0) {
      // No session id — this MUST be an initialize call.
      String method = request.optString("method", "");
      if (!"initialize".equals(method)) {
        sendPlain(resp, 400,
            "Missing " + SESSION_ID_HEADER + " header; send an `initialize` request first");
        return;
      }
      session = McpSessions.createStreamable();
      resp.setHeader(SESSION_ID_HEADER, session.getSessionId());
      bcLog("Streamable open sid=" + session.getSessionId());
    } else {
      session = McpSessions.acquireStreamable(
          sessionIdHeader, BMcpPlatformService.mcpSessionIdleTimeoutMs());
      if (session == null) {
        sendPlain(resp, 404, "Session not found or expired: " + sessionIdHeader);
        return;
      }
    }

    javax.baja.user.BUser resolvedUser = resolveBearerToUser(req);
    JSONObject response = McpProtocol.handle(request, BMcpPlatformService.getRegistry(), session, resolvedUser);
    if (response == null) {
      // Notification — no body to deliver.
      resp.setStatus(202);
      resp.setContentType("text/plain; charset=utf-8");
      resp.getWriter().flush();
      return;
    }

    String body = response.toString();
    resp.setStatus(200);
    resp.setContentType("application/json; charset=utf-8");
    PrintWriter w = resp.getWriter();
    w.write(body);
    w.flush();
  }

  /**
   * GET /mcp — server-initiated push channel.
   *
   * <p>Spec-allowed degenerate implementation: open the SSE response,
   * write a single comment, close. The current tool set produces no
   * server-initiated messages, so there is nothing to push. A future
   * iteration that introduces tool-progress events or sampling requests
   * can replace this with a real {@link StreamableSession}-backed queue
   * (parallel to the existing {@link SseSession} loop).
   */
  private void handleStreamableGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    String sessionIdHeader = req.getHeader(SESSION_ID_HEADER);
    if (sessionIdHeader == null || sessionIdHeader.length() == 0) {
      sendPlain(resp, 400, "Missing " + SESSION_ID_HEADER + " header");
      return;
    }
    StreamableSession session = McpSessions.acquireStreamable(
        sessionIdHeader, BMcpPlatformService.mcpSessionIdleTimeoutMs());
    if (session == null) {
      sendPlain(resp, 404, "Session not found or expired: " + sessionIdHeader);
      return;
    }

    resp.setStatus(200);
    resp.setContentType("text/event-stream; charset=utf-8");
    resp.setHeader("Cache-Control", "no-cache");
    resp.setHeader("Connection", "keep-alive");
    resp.setHeader("X-Accel-Buffering", "no");
    PrintWriter w = resp.getWriter();
    w.write(": stream-open\n\n");
    w.flush();
    bcLog("Streamable GET (no push payload) sid=" + session.getSessionId());
    // Returning ends the response; client will reconnect if/when needed.
  }

  /**
   * DELETE /mcp — explicit session close (MCP spec 2025-06-18).
   * Idempotent: deleting an unknown id still returns 204.
   */
  private void handleStreamableDelete(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    String sessionIdHeader = req.getHeader(SESSION_ID_HEADER);
    if (sessionIdHeader == null || sessionIdHeader.length() == 0) {
      sendPlain(resp, 400, "Missing " + SESSION_ID_HEADER + " header");
      return;
    }
    McpSessions.remove(sessionIdHeader);
    bcLog("Streamable close sid=" + sessionIdHeader);
    resp.setStatus(204);
  }

  private void handleSse(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    resp.setStatus(200);
    resp.setContentType("text/event-stream; charset=utf-8");
    resp.setHeader("Cache-Control", "no-cache");
    resp.setHeader("Connection", "keep-alive");
    resp.setHeader("X-Accel-Buffering", "no");

    SseSession session = McpSessions.createSse();
    bcLog("SSE open sid=" + session.getSessionId());
    try {
      PrintWriter w = resp.getWriter();

      String endpoint = req.getContextPath() + "/messages?sessionId=" + session.getSessionId();
      w.write("event: endpoint\n");
      w.write("data: " + endpoint + "\n\n");
      w.flush();

      long heartbeatMs = Math.max(1, BMcpPlatformService.sseHeartbeatSec()) * 1000L;

      while (!session.isClosed()) {
        String msg;
        try {
          msg = session.poll(heartbeatMs);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          break;
        }
        if (msg == null) {
          w.write(": ping\n\n");
          w.flush();
        } else {
          if ("__CLOSE__".equals(msg)) {
            break;
          }
          w.write("event: message\n");
          w.write("data: " + msg + "\n\n");
          w.flush();
        }

        if (w.checkError()) {
          break;
        }
      }
    } finally {
      McpSessions.remove(session.getSessionId());
      bcLog("SSE close sid=" + session.getSessionId());
    }
  }

  private void handleMessage(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    String sessionId = req.getParameter("sessionId");
    if (sessionId == null || sessionId.length() == 0) {
      sendPlain(resp, 400, "Missing sessionId");
      return;
    }
    Session session = McpSessions.get(sessionId);
    if (!(session instanceof SseSession)) {
      // Either no such session, or the id refers to a non-SSE session
      // (Streamable HTTP) — the legacy /messages endpoint only serves SSE.
      sendPlain(resp, 404, "Unknown sessionId");
      return;
    }
    SseSession sseSession = (SseSession) session;

    JSONObject request;
    try {
      request = readJson(req);
    } catch (Exception e) {
      sendPlain(resp, 400, "Bad JSON: " + e.getMessage());
      return;
    }

    javax.baja.user.BUser resolvedUser = resolveBearerToUser(req);
    JSONObject response = McpProtocol.handle(request, BMcpPlatformService.getRegistry(), sseSession, resolvedUser);
    if (response != null) {
      sseSession.enqueue(response.toString());
    }

    resp.setStatus(202);
    resp.setContentType("text/plain; charset=utf-8");
    resp.getWriter().flush();
  }

  /**
   * GET /niagaramcp/health — unauthenticated health probe for monitoring.
   *
   * <p>Returns 200 with a JSON snapshot when underlying services are healthy,
   * or 503 with the same JSON shape (status field flips to {@code "degraded"})
   * when any of: alarm/history service missing, knowledge file unreadable,
   * or the platform service is disabled.
   *
   * <p>This is the only endpoint without Bearer auth — it intentionally
   * exposes only counts and per-service ok/missing booleans, no station data.
   */
  private static void handleHealth(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    JSONObject out = new JSONObject();
    boolean healthy = true;

    // Service-level
    boolean serviceEnabled = BMcpPlatformService.isEnabled();
    if (!serviceEnabled) healthy = false;

    // Niagara service availability
    JSONArray healthyServices = new JSONArray();
    boolean alarmOk = svcAvailable(BAlarmService.TYPE);
    boolean historyOk = svcAvailable(BHistoryService.TYPE);
    if (alarmOk)   healthyServices.put("alarm");   else healthy = false;
    if (historyOk) healthyServices.put("history"); else healthy = false;
    healthyServices.put("web"); // we are the web servlet — alive by definition

    // Knowledge — persisted as a station-config property, so "readable" is
    // structurally guaranteed once the service is up; just report size.
    long knowledgeSize = -1;
    KnowledgeStore ks = BMcpPlatformService.getKnowledgeStore();
    if (ks != null && ks.exists()) {
      knowledgeSize = ks.sizeBytes();
    }

    out.put("status",  healthy ? "ok" : "degraded");
    out.put("version", GetServerInfoTool.NIAGARAMCP_VERSION);
    long startMs = BMcpPlatformService.getServiceStartTimeMs();
    out.put("uptimeSeconds", startMs == 0 ? 0 : (System.currentTimeMillis() - startMs) / 1000L);
    out.put("knowledgeFileSize", knowledgeSize);
    out.put("sessionCount", McpSessions.activeCount());
    out.put("healthyServices", healthyServices);
    if (!serviceEnabled) out.put("note", "platform service disabled");

    resp.setStatus(healthy ? 200 : 503);
    resp.setContentType("application/json; charset=utf-8");
    PrintWriter w = resp.getWriter();
    w.write(out.toString());
    w.flush();
  }

  /**
   * GET /niagaramcp/knowledge.yaml — the current knowledge document as raw
   * YAML/JSON text. Bearer auth required (unlike /health). Exists so any
   * tool that can make an HTTP request — not just MCP clients — can read
   * or (via PUT) replace the knowledge document without implementing the
   * MCP session/JSON-RPC handshake at all.
   */
  private static void handleKnowledgeGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    KnowledgeStore ks = BMcpPlatformService.getKnowledgeStore();
    if (ks == null) { sendPlain(resp, 503, "Knowledge store not available"); return; }
    resp.setStatus(200);
    resp.setContentType("application/yaml; charset=utf-8");
    PrintWriter w = resp.getWriter();
    w.write(ks.toText());
    w.flush();
  }

  /**
   * PUT /niagaramcp/knowledge.yaml — replace the entire knowledge document
   * with the raw YAML/JSON request body. Equivalent to the
   * {@code importKnowledge} tool with {@code mode=replace}, exposed as a
   * plain HTTP resource instead of an MCP tool call.
   */
  private static void handleKnowledgePut(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    KnowledgeStore ks = BMcpPlatformService.getKnowledgeStore();
    if (ks == null) { sendPlain(resp, 503, "Knowledge store not available"); return; }
    String body = readRawBody(req);
    if (body == null || body.trim().length() == 0) {
      sendPlain(resp, 400, "Empty request body");
      return;
    }
    try {
      Object tree = com.niagaramcp.server.yaml.YamlReader.parse(body);
      com.niagaramcp.server.knowledge.KnowledgeModel newModel =
          com.niagaramcp.server.knowledge.KnowledgeModel.fromTree(tree);
      ks.setModel(newModel);
      ks.save("httpPut", "replace", null);
    } catch (Exception e) {
      sendPlain(resp, 400, "Invalid knowledge document: " + e.getMessage());
      return;
    }
    sendPlain(resp, 200, "OK");
  }

  private static boolean svcAvailable(javax.baja.sys.Type t) {
    try { return Sys.getService(t) != null; } catch (Exception e) { return false; }
  }

  private static boolean checkServiceEnabled(HttpServletResponse resp) throws IOException {
    if (!BMcpPlatformService.isEnabled()) {
      sendPlain(resp, 503, "MCP service disabled");
      return false;
    }
    return true;
  }

  private static boolean checkAuth(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    String expected = BMcpPlatformService.apiToken();
    if (expected == null || expected.length() == 0) {
      sendUnauthorized(resp, "MCP apiToken is not configured on the service");
      return false;
    }
    String header = req.getHeader("Authorization");
    if (header == null || !header.startsWith("Bearer ")) {
      sendUnauthorized(resp, "Missing Bearer token");
      return false;
    }
    String token = header.substring(7).trim();
    // Either matches the read-only service apiToken (legacy / monitoring),
    // or resolves to a BUser via the mcp:tokenHash tag walk (v0.5).
    if (expected.equals(token)) return true;
    if (com.niagaramcp.server.auth.BearerResolver.resolve(token).isPresent()) return true;
    sendUnauthorized(resp, "Invalid token");
    return false;
  }

  /**
   * v0.5: re-resolve the bearer to a {@link javax.baja.user.BUser} if it
   * matches a user's {@code mcp:tokenHash} tag. Returns {@code null} when
   * the bearer matches the service-identity {@code apiToken} (in which
   * case the request runs under service identity — fine for read-only
   * tools, rejected by tools whose {@code requiresUserContext()} is true).
   *
   * <p>Called once per request after {@link #checkAuth} has already
   * validated the token. Re-resolves rather than caching so token rotation
   * takes effect on the next request without per-session invalidation.
   */
  private static javax.baja.user.BUser resolveBearerToUser(HttpServletRequest req) {
    String header = req.getHeader("Authorization");
    if (header == null || !header.startsWith("Bearer ")) return null;
    String token = header.substring(7).trim();
    String apiToken = BMcpPlatformService.apiToken();
    if (apiToken != null && apiToken.equals(token)) return null;  // service identity
    return com.niagaramcp.server.auth.BearerResolver.resolve(token).orElse(null);
  }

  private static void sendUnauthorized(HttpServletResponse resp, String body) throws IOException {
    resp.setHeader("WWW-Authenticate", "Bearer");
    sendPlain(resp, 401, body);
  }

  private static JSONObject readJson(HttpServletRequest req) throws IOException {
    return new JSONObject(new JSONTokener(readRawBody(req)));
  }

  private static String readRawBody(HttpServletRequest req) throws IOException {
    BufferedReader r = new BufferedReader(
        new InputStreamReader(req.getInputStream(), "UTF-8"));
    StringBuilder sb = new StringBuilder();
    String line;
    while ((line = r.readLine()) != null) {
      sb.append(line).append('\n');
    }
    return sb.toString();
  }

  private static String stripSlash(String s) {
    if (s == null) return "";
    if (s.length() > 0 && s.charAt(0) == '/') return s.substring(1);
    return s;
  }

  private static void sendPlain(HttpServletResponse resp, int code, String body) throws IOException {
    resp.setStatus(code);
    resp.setContentType("text/plain; charset=utf-8");
    if (body != null && body.length() > 0) {
      PrintWriter w = resp.getWriter();
      w.write(body);
      w.flush();
    }
  }

  private static void bcLog(String s) {
    if (BMcpPlatformService.showLog()) {
      System.out.println("niagaramcp McpServlet " + s);
    }
  }
}
