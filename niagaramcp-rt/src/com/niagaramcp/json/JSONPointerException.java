package com.niagaramcp.json;

/**
 * Исключение, возникающее при ошибке разрешения JSON-указателя (JSON Pointer, RFC 6901).
 */
public class JSONPointerException extends JSONException {
   private static final long serialVersionUID = 8872944667561856751L;

   /**
    * Создаёт исключение с указанным сообщением.
    *
    * @param message текст сообщения об ошибке
    */
   public JSONPointerException(String message) {
      super(message);
   }

   /**
    * Создаёт исключение с сообщением и причиной.
    *
    * @param message текст сообщения об ошибке
    * @param cause исходное исключение-причина
    */
   public JSONPointerException(String message, Throwable cause) {
      super(message, cause);
   }
}
