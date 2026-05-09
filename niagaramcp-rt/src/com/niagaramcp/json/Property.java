package com.niagaramcp.json;

import java.util.Enumeration;
import java.util.Iterator;
import java.util.Properties;

/**
 * Утилита для преобразования между {@link Properties} и {@link JSONObject}.
 */
public class Property {
   /**
    * Преобразует объект {@link Properties} в {@link JSONObject}.
    *
    * @param properties исходный набор свойств
    * @return JSON-объект с парами имя/значение
    * @throws JSONException при ошибке построения JSON-объекта
    */
   public static JSONObject toJSONObject(Properties properties) throws JSONException {
      JSONObject jo = new JSONObject();
      if (properties != null && !properties.isEmpty()) {
         Enumeration<?> enumProperties = properties.propertyNames();

         while(enumProperties.hasMoreElements()) {
            String name = (String)enumProperties.nextElement();
            jo.put(name, (Object)properties.getProperty(name));
         }
      }

      return jo;
   }

   /**
    * Преобразует {@link JSONObject} в {@link Properties}.
    *
    * @param jo исходный JSON-объект
    * @return набор свойств с парами ключ/значение из JSON
    * @throws JSONException при ошибке чтения JSON-объекта
    */
   public static Properties toProperties(JSONObject jo) throws JSONException {
      Properties properties = new Properties();
      if (jo != null) {
         Iterator iter = jo.keySet().iterator();

         while(iter.hasNext()) {
            String key = (String)iter.next();
            Object value = jo.opt(key);
            if (!JSONObject.NULL.equals(value)) {
               properties.put(key, value.toString());
            }
         }
      }

      return properties;
   }
}
