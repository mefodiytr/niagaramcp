/*
 * Copyright 2026 niagaramcp contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package com.niagaramcp.server.knowledge;

/** Wraps schema violations, missing required fields, and version mismatches. */
public class KnowledgeException extends Exception {
  private static final long serialVersionUID = 1L;
  public KnowledgeException(String message)                   { super(message); }
  public KnowledgeException(String message, Throwable cause)  { super(message, cause); }
}
