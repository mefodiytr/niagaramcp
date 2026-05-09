package com.niagaramcp.json;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Аннотация, задающая альтернативное имя свойства при автоматической сериализации метода в JSON.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD})
public @interface JSONPropertyName {
   /**
    * Имя свойства, используемое в итоговом JSON.
    *
    * @return имя свойства
    */
   String value();
}
