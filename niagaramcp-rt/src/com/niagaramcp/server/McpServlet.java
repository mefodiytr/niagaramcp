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
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import javax.baja.web.servlets.UnauthenticatedServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import com.niagaramcp.json.JSONObject;
import com.niagaramcp.json.JSONTokener;

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
    if (!checkServiceEnabled(resp)) return;
    if (!checkAuth(req, resp)) return;
    String path = stripSlash(req.getPathInfo());
    if ("sse".equals(path)) {
      handleSse(req, resp);
    } else {
      sendPlain(resp, 404, "Not Found: /" + path);
    }
  }

  @Override
  protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    if (!checkServiceEnabled(resp)) return;
    if (!checkAuth(req, resp)) return;
    String path = stripSlash(req.getPathInfo());
    if ("messages".equals(path)) {
      handleMessage(req, resp);
    } else {
      sendPlain(resp, 404, "Not Found: /" + path);
    }
  }

  private void handleSse(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    resp.setStatus(200);
    resp.setContentType("text/event-stream; charset=utf-8");
    resp.setHeader("Cache-Control", "no-cache");
    resp.setHeader("Connection", "keep-alive");
    resp.setHeader("X-Accel-Buffering", "no");

    McpSession session = McpSessions.create();
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
    McpSession session = McpSessions.get(sessionId);
    if (session == null) {
      sendPlain(resp, 404, "Unknown sessionId");
      return;
    }

    JSONObject request;
    try {
      request = readJson(req);
    } catch (Exception e) {
      sendPlain(resp, 400, "Bad JSON: " + e.getMessage());
      return;
    }

    JSONObject response = McpProtocol.handle(request, BMcpPlatformService.getRegistry(), session);
    if (response != null) {
      session.enqueue(response.toString());
    }

    resp.setStatus(202);
    resp.setContentType("text/plain; charset=utf-8");
    resp.getWriter().flush();
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
