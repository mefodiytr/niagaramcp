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
    if ("health".equals(path)) { handleHealth(req, resp); return; }
    if (!checkServiceEnabled(resp)) return;
    if (!checkAuth(req, resp)) return;
    if ("sse".equals(path)) { handleSse(req, resp); return; }
    if ("mcp".equals(path)) { handleStreamableGet(req, resp); return; }
    sendPlain(resp, 404, "Not Found: /" + path);
  }

  @Override
  protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    if (!checkServiceEnabled(resp)) return;
    if (!checkAuth(req, resp)) return;
    String path = stripSlash(req.getPathInfo());
    if ("messages".equals(path)) { handleMessage(req, resp); return; }
    if ("mcp".equals(path))      { handleStreamablePost(req, resp); return; }
    sendPlain(resp, 404, "Not Found: /" + path);
  }

  @Override
  protected void doDelete(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    if (!checkServiceEnabled(resp)) return;
    if (!checkAuth(req, resp)) return;
    String path = stripSlash(req.getPathInfo());
    if ("mcp".equals(path)) { handleStreamableDelete(req, resp); return; }
    sendPlain(resp, 404, "Not Found: /" + path);
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

    JSONObject response = McpProtocol.handle(request, BMcpPlatformService.getRegistry(), session);
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

    JSONObject response = McpProtocol.handle(request, BMcpPlatformService.getRegistry(), sseSession);
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

    // Knowledge file
    long knowledgeSize = -1;
    KnowledgeStore ks = BMcpPlatformService.getKnowledgeStore();
    if (ks != null && ks.getFile() != null) {
      File f = ks.getFile();
      if (f.exists()) {
        if (!f.canRead()) healthy = false;
        knowledgeSize = f.length();
      }
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
    if (!expected.equals(token)) {
      sendUnauthorized(resp, "Invalid token");
      return false;
    }
    return true;
  }

  private static void sendUnauthorized(HttpServletResponse resp, String body) throws IOException {
    resp.setHeader("WWW-Authenticate", "Bearer");
    sendPlain(resp, 401, body);
  }

  private static JSONObject readJson(HttpServletRequest req) throws IOException {
    BufferedReader r = new BufferedReader(
        new InputStreamReader(req.getInputStream(), "UTF-8"));
    StringBuilder sb = new StringBuilder();
    String line;
    while ((line = r.readLine()) != null) {
      sb.append(line).append('\n');
    }
    return new JSONObject(new JSONTokener(sb.toString()));
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
