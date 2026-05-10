/*
 * Copyright 2026 niagaramcp contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.niagaramcp.server;

/**
 * MCP resource provider. Implementations are either static (single fixed
 * URI) or templated (RFC 6570 URI template, parameter substitution).
 */
public interface Resource {

  /** @return the static URI (e.g. {@code "niagara://overview"}), or {@code null} if templated. */
  String uri();

  /** @return RFC 6570 URI template (e.g. {@code "niagara://equipment/{id}"}), or {@code null} if static. */
  String uriTemplate();

  /** Human-readable name advertised to clients. */
  String name();

  /** Optional human-readable description. */
  String description();

  /** Content MIME type (e.g. {@code "application/json"}). */
  String mimeType();

  /** @return {@code true} iff this resource can serve {@code concreteUri}. */
  boolean matches(String concreteUri);

  /** Produce the resource content for {@code concreteUri}. */
  String read(String concreteUri) throws Exception;
}
