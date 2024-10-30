package io.bitdive.parent.trasirovka.agent.utils.fast_json_custom;

import com.alibaba.fastjson.serializer.ValueFilter;
import io.bitdive.parent.anotations.NotMonitoringParamsField;

import java.lang.reflect.Field;

public class CustomValueFilter implements ValueFilter {
    @Override
    public Object process(Object object, String name, Object value) {
        Field field = getField(object.getClass(), name);
        if (field != null) {
            // Handle fields annotated with @NotMonitoringParamsField
            if (field.isAnnotationPresent(NotMonitoringParamsField.class)) {
                NotMonitoringParamsField annotation = field.getAnnotation(NotMonitoringParamsField.class);
                return annotation.value();
            }
            // Handle byte[] fields
            if (value instanceof byte[]) {
                return "byte_array";
            }
        }
        return value;
    }

    private Field getField(Class<?> clazz, String fieldName) {
        // Search for the field, including superclasses
        while (clazz != null) {
            try {
                Field field = clazz.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        return null;
    }
}