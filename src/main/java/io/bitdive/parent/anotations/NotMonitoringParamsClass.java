package io.bitdive.parent.anotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;


@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
public @interface NotMonitoringParamsClass {
    /**
     * A string that will be used to replace the field value during serialization.
     * The default is "*****".
     */
    String value() default "*****";
}
