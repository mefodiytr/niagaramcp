package com.niagaramcp.json;

import java.io.StringWriter;

/**
 * {@link JSONWriter}, результатом работы которого является строка,
 * собираемая во внутренний {@link StringWriter}.
 */
public class JSONStringer extends JSONWriter {
   public JSONStringer() {
      super(new StringWriter());
   }

   public String toString() {
      return this.mode == 'd' ? this.writer.toString() : null;
   }
}
