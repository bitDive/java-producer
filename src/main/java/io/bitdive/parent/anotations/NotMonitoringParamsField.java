package io.bitdive.parent.anotations;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.annotation.ElementType;

/**
 * Аннотация для указания полей, значения которых необходимо маскировать при сериализации.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD})
public @interface NotMonitoringParamsField {
    /**
     * Строка, которая будет использоваться для замены значения поля при сериализации.
     * По умолчанию используется "*****".
     */
    String value() default "*****";
}
