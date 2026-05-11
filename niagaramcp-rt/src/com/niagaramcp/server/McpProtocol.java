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

import com.niagaramcp.json.JSONArray;
import com.niagaramcp.json.JSONObject;
import com.niagaramcp.json.JSONTokener;
import com.niagaramcp.server.tools.Tool;

/**
 * JSON-RPC 2.0 dispatcher. Maps {@code initialize} / {@code ping} /
 * {@code tools/list} / {@code tools/call} requests to the appropriate
 * tool implementation in the {@link ToolRegistry}.
 */
public final class McpProtocol {

  /** Default protocol version. Runtime value comes from
   *  {@link BMcpPlatformService#mcpProtocolVersion()} (overridable via
   *  {@code mcpProtocolVersion} property; default falls back here). */
  public static final String PROTOCOL_VERSION = "2025-06-18";
  public static final String SERVER_NAME = "niagaramcp";
  public static final String SERVER_VERSION = "1.0.0";

  // ----- JSON-RPC standard codes (per spec) ----------------------
  public static final int ERR_PARSE             = -32700;
  public static final int ERR_INVALID_REQUEST   = -32600;
  public static final int ERR_METHOD_NOT_FOUND  = -32601;
  public static final int ERR_INVALID_PARAMS    = -32602;
  public static final int ERR_INTERNAL          = -32603;

  // ----- niagaramcp-implementation-defined codes (v0.3.1) --------
  /** Session not found or expired (used by Streamable HTTP transport). */
  public static final int ERR_SESSION_NOT_FOUND     = -32001;
  /** Tool name not registered (or disabled via property). */
  public static final int ERR_TOOL_NOT_FOUND        = -32002;
  /** Resource URI does not match any registered provider. */
  public static final int ERR_RESOURCE_NOT_FOUND    = -32003;
  /** Knowledge file unreadable (path missing / IO error). */
  public static final int ERR_KNOWLEDGE_UNREADABLE  = -32004;
  /** Schema validation failed for input data (knowledge YAML, tool args). */
  public static final int ERR_SCHEMA_VALIDATION     = -32005;
  /** Niagara ord could not be resolved (no such component). */
  public static final int ERR_ORD_NOT_RESOLVABLE    = -32006;
  /** Point has no BHistoryExt child (readHistory called on history-less point). */
  public static final int ERR_HISTORY_EXT_MISSING   = -32007;
  /** {@link javax.baja.alarm.BAlarmService} unavailable. */
  public static final int ERR_ALARM_SERVICE_MISSING = -32008;
  /** Transport disabled by operator (sseEnabled / streamableEnabled property false). */
  public static final int ERR_TRANSPORT_DISABLED    = -32009;

  // ----- niagaramcp-implementation-defined codes (v0.5) ----------
  /**
   * User-Context operation refused by Niagara permissions. Wraps
   * {@link javax.baja.security.PermissionException}. {@code error.data}
   * carries {@code {user, ord, operation}} captured at the call-site
   * because the underlying PermissionException only exposes a String
   * message.
   */
  public static final int ERR_PERMISSION_DENIED     = -32010;
  /**
   * Bearer token presented does not resolve to any {@code BUser} via
   * the {@code mcp:tokenHash} tag walk over {@code BUserService}. Used
   * for tools whose {@code requiresUserContext()} is true. Distinct from
   * 401 (which fires when no Bearer at all): -32011 fires inside
   * dispatch when auth succeeded against {@code apiToken} but the
   * specific tool needs a user identity instead of service identity.
   */
  public static final int ERR_USER_NOT_FOUND        = -32011;

  // ----- niagaramcp-implementation-defined codes (v0.5.1) --------
  /**
   * {@code removeComponent} refused because the target has at least
   * one inbound {@link javax.baja.sys.BLink} (another component is
   * actively reading from a slot on this one). {@code error.data}
   * carries {@code {ord, inboundLinkCount, sampleSourceOrds[]}}.
   * Operator must unlink first (or call with {@code force=true}).
   */
  public static final int ERR_COMPONENT_HAS_INBOUND_LINKS = -32013;
  /** Action name not found on the target component for {@code invokeAction}. */
  public static final int ERR_ACTION_NOT_FOUND      = -32014;
  /**
   * Extension type cannot be added under the requested parent
   * (parent type doesn't satisfy the extension's required-parent
   * predicate, or extension already present and isn't multi-instance).
   */
  public static final int ERR_EXTENSION_NOT_APPLICABLE = -32015;
  /**
   * {@code linkSlots} refused because the source slot's value type is
   * not assignable to the sink slot's type and the caller did not
   * pass {@code convert=true}. {@code error.data} carries
   * {@code {sourceType, sinkType}}.
   */
  public static final int ERR_LINK_TYPE_MISMATCH    = -32016;

  /**
   * Backward-compatible entry — assumes no user-Context resolved
   * (callers from pre-v0.5 code paths). Tools whose
   * {@code requiresUserContext()} is true will get
   * {@code -32011 ERR_USER_NOT_FOUND}.
   */
  public static JSONObject handle(JSONObject request, ToolRegistry registry, Session session) {
    return handle(request, registry, session, null);
  }

  /**
   * v0.5 entry — accepts the {@link javax.baja.user.BUser} resolved by
   * {@code BearerResolver} from the request's Bearer token.
   * {@code resolvedUser} is {@code null} when the bearer matched the
   * read-only {@code apiToken} (service identity) instead of a real
   * user; tools whose {@code requiresUserContext()} is true reject in
   * that case via {@link #ERR_USER_NOT_FOUND}.
   */
  public static JSONObject handle(JSONObject request, ToolRegistry registry,
                                  Session session, javax.baja.user.BUser resolvedUser) {
    Object id = request.has("id") ? request.get("id") : null;
    boolean isNotification = !request.has("id");
    String method = request.optString("method", "");
    JSONObject params = request.optJSONObject("params");
    if (params == null) {
      params = new JSONObject();
    }

    try {
      if ("initialize".equals(method)) {
        return ok(id, buildInitializeResult());
      }
      if ("notifications/initialized".equals(method)) {
        if (session != null) {
          session.markInitialized();
        }
        return null;
      }
      if ("ping".equals(method)) {
        return ok(id, new JSONObject());
      }
      if ("tools/list".equals(method)) {
        return ok(id, buildToolsList(registry));
      }
      if ("tools/call".equals(method)) {
        return ok(id, callTool(registry, params, resolvedUser, session));
      }
      // v0.3 — resources
      if ("resources/list".equals(method)) {
        return ok(id, buildResourcesList(BMcpPlatformService.getResourceRegistry()));
      }
      if ("resources/templates/list".equals(method)) {
        return ok(id, buildResourceTemplatesList(BMcpPlatformService.getResourceRegistry()));
      }
      if ("resources/read".equals(method)) {
        return ok(id, readResource(BMcpPlatformService.getResourceRegistry(), params));
      }
      // v0.3 — prompts
      if ("prompts/list".equals(method)) {
        return ok(id, buildPromptsList(BMcpPlatformService.getPromptRegistry()));
      }
      if ("prompts/get".equals(method)) {
        return ok(id, getPrompt(BMcpPlatformService.getPromptRegistry(), params));
      }
      if (isNotification) {
        return null;
      }
      JSONObject mdata = new JSONObject();
      mdata.put("method", method);
      return error(id, ERR_METHOD_NOT_FOUND, "Method not found: " + method, mdata);
    } catch (RpcException e) {
      return error(id, e.code, e.getMessage(), e.data);
    } catch (Exception e) {
      return error(id, ERR_INTERNAL, "Internal error: " + e.getMessage(), null);
    }
  }

  private static JSONObject buildInitializeResult() {
    JSONObject caps = new JSONObject();
    caps.put("tools",     new JSONObject());
    caps.put("resources", new JSONObject());
    caps.put("prompts",   new JSONObject());

    JSONObject info = new JSONObject();
    info.put("name", SERVER_NAME);
    // serverInfo.version reflects the niagaramcp module version, not the
    // (legacy) SERVER_VERSION literal — so AI clients see what's actually
    // deployed. Source of truth lives in GetServerInfoTool.NIAGARAMCP_VERSION.
    info.put("version", com.niagaramcp.server.tools.GetServerInfoTool.NIAGARAMCP_VERSION);

    // v0.4: advertise enabled transports (informational; clients still pick
    // by URL). Reflects current sseEnabled / streamableEnabled property values.
    JSONArray transports = new JSONArray();
    if (BMcpPlatformService.sseEnabled())        transports.put("sse");
    if (BMcpPlatformService.streamableEnabled()) transports.put("streamable-http");
    info.put("transports", transports);

    JSONObject result = new JSONObject();
    result.put("protocolVersion", BMcpPlatformService.mcpProtocolVersion());
    result.put("capabilities", caps);
    result.put("serverInfo", info);
    return result;
  }

  // ----- Resources --------------------------------------------------

  private static JSONObject buildResourcesList(ResourceRegistry reg) {
    JSONArray arr = new JSONArray();
    if (reg != null) {
      for (Resource r : reg.staticResources()) {
        JSONObject o = new JSONObject();
        o.put("uri",         r.uri());
        o.put("name",        r.name());
        if (r.description() != null) o.put("description", r.description());
        if (r.mimeType()    != null) o.put("mimeType",    r.mimeType());
        arr.put(o);
      }
    }
    JSONObject out = new JSONObject();
    out.put("resources", arr);
    return out;
  }

  private static JSONObject buildResourceTemplatesList(ResourceRegistry reg) {
    JSONArray arr = new JSONArray();
    if (reg != null) {
      for (Resource r : reg.templates()) {
        JSONObject o = new JSONObject();
        o.put("uriTemplate", r.uriTemplate());
        o.put("name",        r.name());
        if (r.description() != null) o.put("description", r.description());
        if (r.mimeType()    != null) o.put("mimeType",    r.mimeType());
        arr.put(o);
      }
    }
    JSONObject out = new JSONObject();
    out.put("resourceTemplates", arr);
    return out;
  }

  private static JSONObject readResource(ResourceRegistry reg, JSONObject params) {
    if (reg == null) throw new RpcException(ERR_INTERNAL, "Resource registry not initialized");
    String uri = params.optString("uri", "");
    if (uri.length() == 0) throw new RpcException(ERR_INVALID_PARAMS, "Missing 'uri' parameter");
    Resource r = reg.find(uri);
    if (r == null) {
      JSONObject data = new JSONObject();
      data.put("uri", uri);
      throw new RpcException(ERR_RESOURCE_NOT_FOUND, "Unknown resource: " + uri, data);
    }
    String body;
    try {
      body = r.read(uri);
    } catch (Exception e) {
      throw new RpcException(ERR_INTERNAL, "Resource read failed: " + e.getMessage());
    }
    JSONObject content = new JSONObject();
    content.put("uri",      uri);
    content.put("mimeType", r.mimeType() == null ? "text/plain" : r.mimeType());
    content.put("text",     body == null ? "" : body);
    JSONArray arr = new JSONArray();
    arr.put(content);
    JSONObject out = new JSONObject();
    out.put("contents", arr);
    return out;
  }

  // ----- Prompts ----------------------------------------------------

  private static JSONObject buildPromptsList(PromptRegistry reg) {
    JSONArray arr = new JSONArray();
    if (reg != null) {
      for (Prompt p : reg.all()) {
        JSONObject o = new JSONObject();
        o.put("name",        p.name());
        if (p.description() != null) o.put("description", p.description());
        JSONArray pa = p.arguments();
        if (pa != null && pa.length() > 0) o.put("arguments", pa);
        arr.put(o);
      }
    }
    JSONObject out = new JSONObject();
    out.put("prompts", arr);
    return out;
  }

  private static JSONObject getPrompt(PromptRegistry reg, JSONObject params) {
    if (reg == null) throw new RpcException(ERR_INTERNAL, "Prompt registry not initialized");
    String name = params.optString("name", "");
    if (name.length() == 0) throw new RpcException(ERR_INVALID_PARAMS, "Missing 'name'");
    Prompt p = reg.get(name);
    if (p == null) {
      JSONObject data = new JSONObject();
      data.put("promptName", name);
      // No dedicated code for prompts in v0.3.1 set; use INVALID_PARAMS with data.
      throw new RpcException(ERR_INVALID_PARAMS, "Unknown prompt: " + name, data);
    }
    JSONObject promptArgs = params.optJSONObject("arguments");
    if (promptArgs == null) promptArgs = new JSONObject();
    JSONArray messages = p.render(promptArgs);
    JSONObject out = new JSONObject();
    if (p.description() != null) out.put("description", p.description());
    out.put("messages", messages == null ? new JSONArray() : messages);
    return out;
  }

  private static JSONObject buildToolsList(ToolRegistry registry) {
    JSONArray arr = new JSONArray();
    if (registry != null) {
      for (Tool t : registry.all()) {
        JSONObject one = new JSONObject();
        one.put("name", t.name());
        one.put("category", t.getCategory());            // v0.4: client-side grouping
        one.put("description", t.description());
        one.put("inputSchema", new JSONObject(new JSONTokener(t.schemaJson())));
        // v0.5: MCP 2025-06-18 §6.1 tool annotations
        one.put("annotations", t.annotations().toJson());
        // v0.5: niagaramcp extension — auth requirement hint for clients
        // that want to surface "this tool requires a user-Context bearer".
        one.put("requiresUserContext", t.requiresUserContext());
        arr.put(one);
      }
    }
    JSONObject result = new JSONObject();
    result.put("tools", arr);
    return result;
  }

  private static JSONObject callTool(ToolRegistry registry, JSONObject params,
                                     javax.baja.user.BUser resolvedUser, Session session) {
    if (registry == null) {
      throw new RpcException(ERR_INTERNAL, "Tool registry not initialized");
    }
    String name = params.optString("name", "");
    Tool t = registry.get(name);
    if (t == null) {
      JSONObject data = new JSONObject();
      data.put("toolName", name);
      throw new RpcException(ERR_TOOL_NOT_FOUND, "Unknown tool: " + name, data);
    }
    // v0.5: gate tools that require user-Context. Bearer either matched
    // apiToken (resolvedUser=null = service identity) or didn't resolve
    // to a BUser via the mcp:tokenHash walk → reject before dispatch.
    if (t.requiresUserContext() && resolvedUser == null) {
      JSONObject data = new JSONObject();
      data.put("tool", name);
      data.put("requiresUserContext", true);
      throw new RpcException(ERR_USER_NOT_FOUND,
          "Tool '" + name + "' requires a user-Context bearer (apiToken " +
          "matches the service identity, not a BUser)", data);
    }
    JSONObject args = params.optJSONObject("arguments");
    if (args == null) {
      args = new JSONObject();
    }

    // v0.5: stash {resolvedUser, sessionId} in a thread-local so the
    // tool body can pull them when invoking UserContextGateway.run(...).
    // Cleared in finally — never leaks across requests.
    CallContext.set(resolvedUser, session == null ? "" : session.getSessionId());

    String text;
    boolean isError = false;
    int errorCode = 0;            // 0 = no structured code
    JSONObject errorData = null;
    try {
      text = t.call(args);
    } catch (RpcException e) {
      // Tools throw RpcException for their domain errors (e.g. -32014
      // ERR_ACTION_NOT_FOUND). MCP reports tool failures via isError
      // content, not a JSON-RPC error — so carry the code/data forward
      // as extension fields on the result rather than dropping them.
      text = e.getMessage();
      isError = true;
      errorCode = e.code;
      errorData = e.data;
    } catch (Exception e) {
      text = "Error: " + e.getMessage();
      isError = true;
    } finally {
      CallContext.clear();
    }

    JSONObject content = new JSONObject();
    content.put("type", "text");
    content.put("text", (text == null) ? "" : text);

    JSONArray contentArr = new JSONArray();
    contentArr.put(content);

    JSONObject result = new JSONObject();
    result.put("content", contentArr);

    // v0.5: auto-promote JSON-shaped text result to structuredContent
    // per MCP 2025-06-18 §5.4. Pure addition — old MCP clients keep
    // reading content[0].text exactly as before; new clients prefer the
    // typed structuredContent. Tools that return non-JSON text (errors,
    // plain string responses) skip this branch silently.
    if (!isError && text != null && !text.isEmpty()) {
      char first = text.charAt(0);
      if (first == '{') {
        try {
          result.put("structuredContent", new JSONObject(new JSONTokener(text)));
        } catch (Exception ignored) { /* not valid JSON object — text-only */ }
      }
    }
    result.put("isError", isError);
    // v0.5.1: when the tool error was a structured RpcException, expose its
    // code (and any data payload) so clients can branch programmatically on
    // -32013/-32014/-32015/-32016/... instead of string-matching the text.
    if (isError && errorCode != 0) {
      result.put("errorCode", errorCode);
      if (errorData != null) result.put("errorData", errorData);
    }
    return result;
  }

  private static JSONObject ok(Object id, JSONObject result) {
    JSONObject r = new JSONObject();
    r.put("jsonrpc", "2.0");
    r.put("id", (id == null) ? JSONObject.NULL : id);
    r.put("result", result);
    return r;
  }

  private static JSONObject error(Object id, int code, String message, JSONObject data) {
    JSONObject err = new JSONObject();
    err.put("code", code);
    err.put("message", message);
    if (data != null) err.put("data", data);

    JSONObject r = new JSONObject();
    r.put("jsonrpc", "2.0");
    r.put("id", (id == null) ? JSONObject.NULL : id);
    r.put("error", err);
    return r;
  }

  /**
   * RPC-level exception carrying an MCP error code (one of the {@code ERR_*}
   * constants on this class) and an optional {@code data} payload. Promoted
   * to {@code public} in v0.5 so {@link com.niagaramcp.server.auth.UserContextGateway}
   * can throw it from a sibling package.
   */
  public static final class RpcException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public final int code;
    public final JSONObject data;

    public RpcException(int code, String msg) {
      this(code, msg, null);
    }

    public RpcException(int code, String msg, JSONObject data) {
      super(msg);
      this.code = code;
      this.data = data;
    }
  }
}
