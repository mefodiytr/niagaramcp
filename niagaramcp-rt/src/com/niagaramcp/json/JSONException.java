package com.niagaramcp.json;

/**
 * Исключение, возникающее при ошибках парсинга или обработки JSON-данных.
 */
public class JSONException extends RuntimeException {
   private static final long serialVersionUID = 0L;

   /**
    * Создаёт исключение с указанным сообщением.
    *
    * @param message текст сообщения об ошибке
    */
   public JSONException(String message) {
      super(message);
   }

   /**
    * Создаёт исключение с сообщением и причиной.
    *
    * @param message текст сообщения об ошибке
    * @param cause исходное исключение-причина
    */
   public JSONException(String message, Throwable cause) {
      super(message, cause);
   }

   /**
    * Создаёт исключение на основе переданного Throwable.
    *
    * @param cause исходное исключение-причина
    */
   public JSONException(Throwable cause) {
      super(cause.getMessage(), cause);
   }
}
