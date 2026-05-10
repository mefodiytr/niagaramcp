/*
 * Copyright 2026 niagaramcp contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.niagaramcp.server.auth;

import javax.baja.data.BIDataValue;
import javax.baja.sys.BString;
import javax.baja.sys.Sys;
import javax.baja.user.BUser;
import javax.baja.user.BUserService;
import com.niagaramcp.server.BMcpPlatformService;

import java.util.Optional;

/**
 * Resolves an HTTP {@code Authorization: Bearer …} header value to the
 * corresponding {@link BUser} on the station, by walking
 * {@link BUserService#getUsers()} and constant-time comparing the
 * salted SHA-256 hash of the bearer against each user's
 * {@code mcp:tokenHash} tag.
 *
 * <p>Returns {@link Optional#empty()} when no user matches, the bearer
 * is empty, the {@code tokenSalt} hasn't been generated yet (service
 * not started), or {@link BUserService} is unavailable. Distinguishing
 * those failure modes is intentionally not exposed — callers map all of
 * them to {@code -32011 ERR_USER_NOT_FOUND} (or HTTP 401 if no Bearer
 * was sent at all).
 *
 * <p><b>Timing safety:</b> the walk is unconditional. We do not
 * early-exit on first match, and we run a constant-time compare for
 * every user even when their {@code mcp:tokenHash} tag is absent
 * (compared against an empty string). Without that, walk duration
 * would leak which usernames carry an MCP enrollment.
 *
 * <p><b>Cost:</b> O(N) where N = total users on the station. With
 * ~50–200 users typical for a Niagara installation, the per-request
 * overhead is microseconds — negligible against the network round-trip.
 * No caching: cache invalidation on token rotation would itself be a
 * concurrency primitive, and the linear scan is fast enough.
 */
public final class BearerResolver {

  private BearerResolver() {}

  /**
   * @param bearer the raw bearer token string (everything after
   *               {@code "Bearer "} in the Authorization header)
   * @return the matching BUser, or empty if none
   */
  public static Optional<BUser> resolve(String bearer) {
    if (bearer == null || bearer.isEmpty()) return Optional.empty();

    String salt = BMcpPlatformService.tokenSalt();
    if (salt == null || salt.isEmpty()) return Optional.empty();

    String expectedHash;
    try {
      expectedHash = TokenHasher.hash(bearer, salt);
    } catch (Exception e) {
      return Optional.empty();
    }

    BUserService userService;
    try {
      userService = (BUserService) Sys.getService(BUserService.TYPE);
    } catch (Exception e) {
      return Optional.empty();
    }
    if (userService == null) return Optional.empty();

    BUser[] users = userService.getUsers();
    if (users == null || users.length == 0) return Optional.empty();

    BUser found = null;
    for (int i = 0; i < users.length; i++) {
      BUser u = users[i];
      String userHash = readTokenHashTag(u);
      // Always compare — even when tag absent (userHash="") — to keep
      // the per-iteration timing identical regardless of enrollment.
      boolean match = TokenHasher.constantTimeEquals(userHash, expectedHash);
      if (match && found == null) {
        // Don't break: keep walking to preserve total-time invariance.
        found = u;
      }
    }
    return Optional.ofNullable(found);
  }

  /**
   * @return the {@code mcp:tokenHash} tag value as a String, or empty
   *         string if the tag is absent or carries a non-BString value.
   */
  private static String readTokenHashTag(BUser u) {
    try {
      Optional<BIDataValue> opt = u.tags().get(McpTags.TOKEN_HASH_ID);
      if (!opt.isPresent()) return "";
      BIDataValue v = opt.get();
      if (v instanceof BString) return ((BString) v).getString();
      // Some other BIDataValue subtype — fall back to toString() rather
      // than failing; comparison will then almost certainly miss.
      return v.toString();
    } catch (Exception e) {
      return "";
    }
  }
}
