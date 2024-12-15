package io.bitdive.parent.anotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation on the indications of fields whose values must be masked during serialization.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD})
public @interface NotMonitoringParamsField {
    /**
     * A string that will be used to replace the field value during serialization.
     * The default is "*****".
     */
    String value() default "*****";
}
