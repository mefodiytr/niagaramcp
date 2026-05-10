package com.niagaramcp.json;

/**
 * Exception raised on JSON parse or processing failures.
 */
public class JSONException extends RuntimeException {
   private static final long serialVersionUID = 0L;

   /**
    * Construct with the given message.
    *
    * @param message error description
    */
   public JSONException(String message) {
      super(message);
   }

   /**
    * Construct with the given message and underlying cause.
    *
    * @param message error description
    * @param cause   underlying exception
    */
   public JSONException(String message, Throwable cause) {
      super(message, cause);
   }

   /**
    * Construct from an underlying Throwable, copying its message.
    *
    * @param cause underlying exception
    */
   public JSONException(Throwable cause) {
      super(cause.getMessage(), cause);
   }
}
