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
package com.niagaramcp.server.tools;

import javax.baja.collection.BITable;
import javax.baja.collection.Column;
import javax.baja.collection.ColumnList;
import javax.baja.collection.TableCursor;
import javax.baja.naming.BOrd;
import javax.baja.sys.BObject;
import com.niagaramcp.json.JSONObject;

/**
 * Runs a BQL query and returns the resulting {@link BITable} as TSV.
 * Bounded by {@link #MAX_LIMIT} rows and a {@link #ITERATION_TIMEOUT_MS}
 * cursor-iteration timeout (10 s).
 */
public final class BqlQueryTool implements Tool {

  private static final int DEFAULT_LIMIT = 100;
  private static final int MAX_LIMIT = 1000;
  private static final long ITERATION_TIMEOUT_MS = 10_000L;

  @Override
  public String name() {
    return "bqlQuery";
  }

  @Override
  public String description() {
    return "Выполнить BQL-запрос к станции и получить таблицу с результатами. На вход полный ord с BQL-частью, например 'station:|slot:/Drivers|bql:select displayName, out from control:ControlPoint'. Результат — TSV: первая строка — имена колонок, далее строки данных. Параметр limit ограничивает число строк (по умолчанию 100, максимум 1000).";
  }

  @Override
  public String schemaJson() {
    return "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\",\"description\":\"Полный ord с BQL-частью\"},\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":1000,\"description\":\"Максимум строк в ответе (по умолчанию 100)\"}},\"required\":[\"query\"]}";
  }

  @Override
  @SuppressWarnings("unchecked")
  public String call(JSONObject args) throws Exception {
    String query = args.optString("query", "");
    if (query == null || query.length() == 0) {
      throw new IllegalArgumentException("Параметр 'query' обязателен");
    }
    int limit = DEFAULT_LIMIT;
    if (args.has("limit") && !args.isNull("limit")) {
      limit = args.getInt("limit");
      if (limit < 1) limit = 1;
      if (limit > MAX_LIMIT) limit = MAX_LIMIT;
    }

    BObject obj = BOrd.make(query).get();
    if (!(obj instanceof BITable)) {
      throw new IllegalArgumentException(
          "Ord не вернул таблицу (BITable). Тип: " +
          ((obj == null) ? "null" : obj.getClass().getSimpleName()));
    }

    BITable<BObject> table = (BITable<BObject>) obj;
    ColumnList columns = table.getColumns();

    StringBuilder sb = new StringBuilder();

    for (int i = 0; i < columns.size(); i++) {
      if (i > 0) sb.append('\t');
      sb.append(columns.get(i).getDisplayName(null));
    }
    sb.append('\n');

    TableCursor<?> cursor = table.cursor();
    int rows = 0;
    boolean truncatedByLimit = false;
    boolean truncatedByTimeout = false;
    long startMs = System.currentTimeMillis();
    while (cursor.next()) {
      if (rows >= limit) {
        truncatedByLimit = true;
        break;
      }
      if (System.currentTimeMillis() - startMs >= ITERATION_TIMEOUT_MS) {
        truncatedByTimeout = true;
        break;
      }
      for (int j = 0; j < columns.size(); j++) {
        if (j > 0) sb.append('\t');
        Column c = columns.get(j);
        Object cell;
        try {
          cell = cursor.cell(c);
        } catch (Exception e) {
          cell = "<err: " + e.getMessage() + ">";
        }
        sb.append((cell == null) ? "" : escape(cell.toString()));
      }
      sb.append('\n');
      rows++;
    }
    sb.append("\n[rows=").append(rows);
    if (truncatedByLimit) sb.append(", truncated at limit=").append(limit);
    if (truncatedByTimeout) sb.append(", truncated due to ")
        .append(ITERATION_TIMEOUT_MS / 1000).append("s timeout");
    sb.append(']');
    return sb.toString();
  }

  private static String escape(String s) {
    return s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
  }
}
