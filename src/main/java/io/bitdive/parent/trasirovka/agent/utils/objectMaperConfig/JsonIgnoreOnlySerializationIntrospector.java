package io.bitdive.parent.trasirovka.agent.utils.objectMaperConfig;

import com.fasterxml.jackson.databind.introspect.AnnotatedMember;
import com.fasterxml.jackson.databind.introspect.NopAnnotationIntrospector;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

public class JsonIgnoreOnlySerializationIntrospector extends NopAnnotationIntrospector {

    private static final String JSON_IGNORE_NAMES = "io.bitdive.shaded.com.fasterxml.jackson.annotation.JsonIgnore";

    @Override
    public boolean hasIgnoreMarker(AnnotatedMember m) {
        Annotation[] annotations = m.getAnnotated().getDeclaredAnnotations();
        for (Annotation ann : annotations) {
            String annotationClassName = ann.annotationType().getName();
            if (JSON_IGNORE_NAMES.contains(annotationClassName)) {
                return readJsonIgnoreValue(ann);
            }
        }
        return false;
    }

    private boolean readJsonIgnoreValue(Annotation ann) {
        try {
            Method valueMethod = ann.annotationType().getMethod("value");
            Object value = valueMethod.invoke(ann);
            return !(value instanceof Boolean) || (Boolean) value;
        } catch (Exception e) {
            // Если не удалось прочитать value(), считаем аннотацию активной
            return true;
        }
    }
}