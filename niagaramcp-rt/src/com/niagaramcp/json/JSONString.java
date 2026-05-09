package com.niagaramcp.json;

/**
 * Интерфейс для объектов, умеющих самостоятельно сериализоваться в JSON-строку.
 */
public interface JSONString {
   /**
    * Возвращает JSON-представление объекта в виде строки.
    *
    * @return строка, содержащая корректный JSON
    */
   String toJSONString();
}
