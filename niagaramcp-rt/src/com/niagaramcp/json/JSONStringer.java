package com.niagaramcp.json;

import java.io.StringWriter;

/**
 * A {@link JSONWriter} whose output is a String collected into an
 * internal {@link StringWriter}.
 */
public class JSONStringer extends JSONWriter {
   public JSONStringer() {
      super(new StringWriter());
   }

   public String toString() {
      return this.mode == 'd' ? this.writer.toString() : null;
   }
}
