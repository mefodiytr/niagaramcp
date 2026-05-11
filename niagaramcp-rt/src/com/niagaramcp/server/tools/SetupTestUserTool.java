/*
 * Copyright 2026 niagaramcp contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.niagaramcp.server.tools;

import javax.baja.data.BIDataValue;
import javax.baja.sys.BString;
import javax.baja.sys.Sys;
import javax.baja.user.BUser;
import javax.baja.user.BUserService;
import com.niagaramcp.json.JSONObject;
import com.niagaramcp.server.BMcpPlatformService;
import com.niagaramcp.server.McpProtocol;
import com.niagaramcp.server.auth.McpTags;
import com.niagaramcp.server.auth.TokenHasher;

import java.util.Optional;

/**
 * Test-only helper used by the smoke client to bind a freshly-generated
 * MCP token (provided as plaintext in the tool args) to a pre-created
 * {@link BUser}'s {@code mcp:tokenHash} tag, so the next request from
 * the smoke runner can authenticate as that user.
 *
 * <h3>Why a tool, not a Workbench action</h3>
 * Smoke runs unattended over MCP; provisioning steps need to be
 * MCP-callable. Real-mutation step 25 wants reflection-real Gateway +
 * AuditWriter + permission-check, not dryRun. Without this tool, the
 * pre-flight would require an operator-side bog-fragment or
 * Workbench scripting before every smoke run.
 *
 * <h3>Why gated by enableTestSetup</h3>
 * In production this tool would be a credential-stuffing primitive
 * (anyone with apiToken could bind their own hash to any BUser).
 * Default {@code enableTestSetup=false} on {@code BMcpPlatformService}
 * keeps it inert. Operator flips to true only on stations used for
 * smoke / CI, and back to false after.
 *
 * <h3>What this tool does NOT do</h3>
 * Does not create the BUser. Operator pre-creates {@code mcpSmokeUser}
 * (or the chosen username) once via Workbench, granting it the
 * write permissions the smoke run needs (e.g. add-permission on
 * {@code /Drivers/}). This avoids putting user-creation in any
 * MCP-reachable surface.
 *
 * <h3>Args</h3>
 * <pre>
 * {
 *   "username": "mcpSmokeUser",        // required
 *   "token":    "&lt;plaintext bearer&gt;"   // required, smoke generates fresh
 * }
 * </pre>
 *
 * <h3>Return</h3>
 * {@code {userOrd, hashSet:true}} on success.
 *
 * <h3>Errors</h3>
 * <ul>
 *   <li>{@code -32602} — missing args / disabled by enableTestSetup=false /
 *       requested user not found in UserService</li>
 *   <li>{@code -32603} — internal (tag write failed)</li>
 * </ul>
 *
 * <p>{@code requiresUserContext()=false} on purpose — caller authenticates
 * via {@code apiToken} (service identity); this tool intentionally runs
 * under service identity to bind tags before any user-Bearer exists.
 * Annotations: {@link ToolAnnotations#MUTATION} (mutates a BUser tag).
 */
public final class SetupTestUserTool implements Tool {

  @Override public String name()                  { return "setupTestUser"; }
  @Override public String getCategory()           { return "diagnostic"; }
  @Override public boolean requiresUserContext()  { return false; }
  @Override public ToolAnnotations annotations()  { return ToolAnnotations.MUTATION; }

  @Override public String description() {
    return "TEST-ONLY (gated by BMcpPlatformService.enableTestSetup). " +
           "Bind an mcp:tokenHash tag to a pre-created BUser so the smoke " +
           "client can authenticate as that user. Args: {username, token}. " +
           "Refuses unless enableTestSetup=true.";
  }

  @Override public String schemaJson() {
    return ToolSchemaHelpers.objectSchema(
        new String[]{"username", "token"},
        "username", ToolSchemaHelpers.stringParam(
            "Name of a pre-existing BUser (operator must create via Workbench first)."),
        "token",    ToolSchemaHelpers.stringParam(
            "Plaintext bearer token to bind. Hashed with the service tokenSalt before storage."));
  }

  @Override
  public String call(JSONObject args) throws Exception {
    if (!BMcpPlatformService.enableTestSetup()) {
      JSONObject d = new JSONObject();
      d.put("hint", "Set BMcpPlatformService.enableTestSetup=true to enable; flip back after smoke.");
      throw new McpProtocol.RpcException(McpProtocol.ERR_INVALID_PARAMS,
          "setupTestUser is gated by enableTestSetup property (currently false)", d);
    }
    final String username = args.optString("username", "");
    final String token    = args.optString("token", "");
    if (username.isEmpty() || token.isEmpty()) {
      throw new McpProtocol.RpcException(McpProtocol.ERR_INVALID_PARAMS,
          "Both 'username' and 'token' are required", null);
    }

    BUserService usvc = (BUserService) Sys.getService(BUserService.TYPE);
    if (usvc == null) {
      throw new McpProtocol.RpcException(McpProtocol.ERR_INTERNAL,
          "UserService unavailable", null);
    }
    BUser user = usvc.getUser(username);
    if (user == null) {
      JSONObject d = new JSONObject();
      d.put("username", username);
      d.put("hint", "Operator must pre-create this BUser via Workbench, granting write permissions for the smoke scope.");
      throw new McpProtocol.RpcException(McpProtocol.ERR_INVALID_PARAMS,
          "User not found: " + username, d);
    }

    String salt = BMcpPlatformService.tokenSalt();
    String hash = TokenHasher.hash(token, salt);

    boolean tagsSetReturned;
    try {
      tagsSetReturned = user.tags().set(McpTags.tokenHashTag(hash));
    } catch (Exception e) {
      throw new McpProtocol.RpcException(McpProtocol.ERR_INTERNAL,
          "Failed to set mcp:tokenHash tag: " + e.getMessage(), null);
    }

    // Read back IMMEDIATELY to verify persistence in-process. If the
    // underlying Tags impl silently dropped the write (no registered
    // dictionary, missing cx, ...), the readback will be empty even
    // though set() returned true.
    String readbackHash = "";
    try {
      Optional<BIDataValue> opt = user.tags().get(McpTags.TOKEN_HASH_ID);
      if (opt.isPresent()) {
        BIDataValue v = opt.get();
        readbackHash = (v instanceof BString)
            ? ((BString) v).getString()
            : v.toString();
      }
    } catch (Exception ignored) {
      // Readback failure leaves readbackHash="" — surfaces in result diag.
    }

    JSONObject result = new JSONObject();
    result.put("userOrd",            "station:|slot:/UserService/" + username);
    result.put("hashSet",            true);
    // Diagnostic fields (added in v0.5.1 fix-up after first real-station
    // smoke run) — let the operator verify whether the tag write actually
    // persisted in-process. saltLen + expectedHash + readbackHash should
    // satisfy: readbackHash == expectedHash. If not, the Tags.set()
    // contract on this Niagara version doesn't persist without cx /
    // dictionary registration / .bog flush; we'll need to revise the
    // write path before user-Bearer auth can work.
    result.put("tagsSetReturned",    tagsSetReturned);
    result.put("expectedHash",       hash);
    result.put("readbackHash",       readbackHash);
    result.put("readbackMatches",    hash.equals(readbackHash));
    result.put("saltLen",            salt.length());
    return result.toString();
  }
}
