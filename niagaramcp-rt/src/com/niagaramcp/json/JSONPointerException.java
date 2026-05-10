package com.niagaramcp.json;

/**
 * Exception raised on JSON Pointer (RFC 6901) resolution failure.
 */
public class JSONPointerException extends JSONException {
   private static final long serialVersionUID = 8872944667561856751L;

   /**
    * Construct with the given message.
    *
    * @param message error description
    */
   public JSONPointerException(String message) {
      super(message);
   }

   /**
    * Construct with the given message and underlying cause.
    *
    * @param message error description
    * @param cause   underlying exception
    */
   public JSONPointerException(String message, Throwable cause) {
      super(message, cause);
   }
}
