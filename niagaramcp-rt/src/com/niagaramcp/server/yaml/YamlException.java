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
package com.niagaramcp.server.yaml;

/**
 * Checked exception for YAML/JSON read failures with optional
 * line/column pointer for operator diagnostics.
 */
public class YamlException extends Exception {

  private static final long serialVersionUID = 1L;

  private final int line;
  private final int col;

  public YamlException(String message) {
    this(message, -1, -1);
  }

  public YamlException(String message, int line, int col) {
    super(formatMessage(message, line, col));
    this.line = line;
    this.col = col;
  }

  public YamlException(String message, int line, int col, Throwable cause) {
    super(formatMessage(message, line, col), cause);
    this.line = line;
    this.col = col;
  }

  public int getLine() { return line; }
  public int getCol()  { return col;  }

  private static String formatMessage(String msg, int line, int col) {
    if (line < 0) return msg;
    if (col < 0)  return "line " + line + ": " + msg;
    return "line " + line + " col " + col + ": " + msg;
  }
}
