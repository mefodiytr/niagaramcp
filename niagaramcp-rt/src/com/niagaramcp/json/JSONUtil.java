package com.niagaramcp.json;

import java.util.AbstractList;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Вспомогательные методы для работы с {@link JSONObject} и {@link JSONArray}:
 * создание неизменяемых обёрток в виде {@link List} и {@link Map}.
 */
public final class JSONUtil {
   private JSONUtil() {
   }

   public static <T> List<T> toUnmodifiableList(final JSONArray array) {
      return new AbstractList<T>() {
         public T get(int index) {
            return (T) array.get(index);
         }

         public int size() {
            return array.length();
         }
      };
   }

   public static <K, V> Map<K, V> toUnmodifiableMap(final JSONObject obj) {
      return new AbstractMap<K, V>() {
         public Set<Map.Entry<K, V>> entrySet() {
            return new AbstractSet<Map.Entry<K, V>>() {
               public Iterator<Map.Entry<K, V>> iterator() {
                  final Iterator<String> keys = obj.keys();
                  return new Iterator<Map.Entry<K, V>>() {
                     public boolean hasNext() {
                        return keys.hasNext();
                     }

                     public Map.Entry<K, V> next() {
                        final String key = (String)keys.next();
                        return new Map.Entry<K, V>() {
                           public K getKey() {
                              return (K) key;
                           }

                           public V getValue() {
                              return (V) obj.get(key);
                           }

                           public V setValue(V value) {
                              throw new UnsupportedOperationException();
                           }
                        };
                     }
                  };
               }

               public int size() {
                  return obj.length();
               }
            };
         }
      };
   }

   public static Iterator<String> sortedKeys(JSONObject obj) {
      return (new TreeSet(obj.keySet())).iterator();
   }

   public static String getString(JSONObject obj, String key) {
      return obj.get(key).toString();
   }

   public static String getString(JSONArray jsonArray, int index) {
      return jsonArray.get(index).toString();
   }
}
