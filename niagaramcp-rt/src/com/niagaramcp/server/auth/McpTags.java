/*
 * Copyright 2026 niagaramcp contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.niagaramcp.server.auth;

import javax.baja.sys.Sys;
import javax.baja.tag.Id;
import javax.baja.tag.Tag;

/**
 * Tag schema for MCP user identity.
 *
 * <p>Currently a single tag: {@code mcp:tokenHash} on a {@code BUser},
 * carrying the salted SHA-256 hash of that user's MCP bearer token
 * (see {@link TokenHasher}).
 *
 * <p><b>Tag write/read works without TagDictionary registration.</b>
 * The dictionary is only needed for Workbench TagBrowser to show the
 * {@code mcp:} namespace as a known concept; it does not gate the
 * tag's existence on the BUser. {@link #attemptDictionaryBootstrap()}
 * is best-effort and intentionally simple: if it cannot register, it
 * logs once and proceeds — token auth still works.
 *
 * <p>Programmatic registration of a {@code BTagDictionary} via
 * {@code BTagDictionaryService} requires constructing a populated
 * {@code BTagInfoList} (tag definitions + group definitions + valid-
 * entity predicates) — that's a self-contained domain that would
 * itself be a multi-commit feature. Deferred to v0.5.x or later. The
 * production-equivalent path right now is for the operator to drop a
 * one-property {@code BTagDictionaryFile} under
 * {@code Services/TagDictionaryService} via Workbench (see
 * {@code samples/README.md} v0.5 section).
 */
public final class McpTags {

  /** Namespace used by all niagaramcp tags. */
  public static final String NAMESPACE = "mcp";

  /**
   * Identity of the per-user token hash tag, written to {@code BUser}
   * components by the {@code generateUserToken} workbench action and
   * the {@code rotateMcpToken} MCP tool (added in v0.5.x).
   */
  public static final Id TOKEN_HASH_ID = Id.newId(NAMESPACE, "tokenHash");

  private McpTags() {}

  /** Construct a {@code mcp:tokenHash} tag with the given hex hash value. */
  public static Tag tokenHashTag(String hashHex) {
    return Tag.newTag(TOKEN_HASH_ID.getQName(), hashHex);
  }

  /**
   * Best-effort TagDictionary registration. Returns {@code true} if a
   * dictionary for the {@link #NAMESPACE} namespace is present after
   * the call (whether we registered it or it was already there).
   *
   * <p>Current implementation: reflection-only check that
   * {@code javax.baja.tagdictionary.BTagDictionaryService} is loadable
   * AND that a dictionary for namespace {@code "mcp"} is already
   * registered. We do NOT construct one programmatically — see class
   * javadoc for why. Returns {@code false} both when the service
   * module is missing and when it's present but no {@code mcp}
   * dictionary is registered; caller logs once.
   */
  public static boolean attemptDictionaryBootstrap() {
    try {
      Class<?> svcCls = Class.forName("javax.baja.tagdictionary.BTagDictionaryService");
      java.lang.reflect.Field typeField = svcCls.getField("TYPE");
      Object type = typeField.get(null);
      Object svc = Sys.getService((javax.baja.sys.Type) type);
      if (svc == null) return false;
      java.lang.reflect.Method getDict = svcCls.getMethod("getTagDictionary", String.class);
      Object opt = getDict.invoke(svc, NAMESPACE);
      // java.util.Optional — isPresent()
      java.lang.reflect.Method isPresent = opt.getClass().getMethod("isPresent");
      return Boolean.TRUE.equals(isPresent.invoke(opt));
    } catch (ClassNotFoundException e) {
      // tagdictionary-rt not installed on this station — that's fine.
      return false;
    } catch (Exception e) {
      // Any other reflection mishap — surface false; caller logs.
      return false;
    }
  }
}
