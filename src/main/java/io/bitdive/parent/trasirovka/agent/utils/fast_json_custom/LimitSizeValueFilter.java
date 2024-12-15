package io.bitdive.parent.trasirovka.agent.utils.fast_json_custom;

import com.alibaba.fastjson.serializer.ValueFilter;
import io.bitdive.parent.anotations.NotMonitoringParamsField;
import io.bitdive.parent.parserConfig.YamlParserConfig;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.*;

public class LimitSizeValueFilter implements ValueFilter {
    private static final int MAX_SIZE = YamlParserConfig.getProfilingConfig().getMonitoring().getSerialization().getMaxElementCollection();
    private static final String INDICATOR = "...";

    private static final Set<String> SENSITIVE_KEYWORDS = new HashSet<>(Arrays.asList(
            "password",
            "pass",
            "secret",
            "token",
            "key",
            "apiKey",
            "auth",
            "credential"
    ));


    @Override
    public Object process(Object object, String name, Object value) {
        if (value == null || object == null) {
            return value;
        }

        if (value instanceof byte[]) {
            return "byte_array";
        }

        Field field = getField(object.getClass(), name);
        if (field != null) {
            if (field.isAnnotationPresent(NotMonitoringParamsField.class)) {
                NotMonitoringParamsField annotation = field.getAnnotation(NotMonitoringParamsField.class);
                return annotation.value();
            }

        }

        if (isSensitiveField(name)) {
            return "******";
        }

        if (value instanceof Collection<?>) {
            return limitCollection((Collection<?>) value);
        }

        if (value instanceof Map<?, ?>) {
            return limitMap((Map<?, ?>) value);
        }

        if (value.getClass().isArray() && !value.getClass().getComponentType().isPrimitive()) {
            return limitArray((Object[]) value);
        }

        if (value.getClass().isArray()) {
            return limitPrimitiveArray(value);
        }


        return value;
    }

    private boolean isSensitiveField(String fieldName) {
        String lowerCaseField = fieldName.toLowerCase();
        for (String keyword : SENSITIVE_KEYWORDS) {
            if (lowerCaseField.contains(keyword.toLowerCase())) {
                return true;
            }
        }
        return false;
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

    private Object limitCollection(Collection<?> original) {
        if (original.size() > MAX_SIZE) {
            List<Object> limited = new ArrayList<>(MAX_SIZE + 1);
            Iterator<?> iterator = original.iterator();
            int count = 0;
            while (iterator.hasNext() && count < MAX_SIZE) {
                limited.add(iterator.next());
                count++;
            }
            limited.add(INDICATOR);
            return limited;
        }
        return original;
    }

    private Object limitMap(Map<?, ?> original) {
        if (original.size() > MAX_SIZE) {
            Map<Object, Object> limited = new LinkedHashMap<>(MAX_SIZE + 1);
            Iterator<? extends Map.Entry<?, ?>> iterator = original.entrySet().iterator();
            int count = 0;
            while (iterator.hasNext() && count < MAX_SIZE) {
                Map.Entry<?, ?> entry = iterator.next();
                limited.put(entry.getKey(), entry.getValue());
                count++;
            }
            limited.put(INDICATOR, INDICATOR);
            return limited;
        }
        return original;
    }

    private Object limitArray(Object[] original) {
        if (original.length > MAX_SIZE) {
            Object[] limited = Arrays.copyOf(original, MAX_SIZE + 1);
            limited[MAX_SIZE] = INDICATOR;
            return limited;
        }
        return original;
    }

    private Object limitPrimitiveArray(Object originalArray) {
        int length = Array.getLength(originalArray);
        if (length > MAX_SIZE) {
            Class<?> componentType = originalArray.getClass().getComponentType();
            Object limitedArray = Array.newInstance(componentType, MAX_SIZE + 1);
            System.arraycopy(originalArray, 0, limitedArray, 0, MAX_SIZE);

            if (componentType.equals(int.class)) {
                Array.setInt(limitedArray, MAX_SIZE, 0);
            } else if (componentType.equals(long.class)) {
                Array.setLong(limitedArray, MAX_SIZE, 0L);
            } else if (componentType.equals(double.class)) {
                Array.setDouble(limitedArray, MAX_SIZE, 0.0);
            } else if (componentType.equals(float.class)) {
                Array.setFloat(limitedArray, MAX_SIZE, 0.0f);
            } else if (componentType.equals(boolean.class)) {
                Array.setBoolean(limitedArray, MAX_SIZE, false);
            } else if (componentType.equals(char.class)) {
                Array.setChar(limitedArray, MAX_SIZE, '\0');
            } else if (componentType.equals(byte.class)) {
                Array.setByte(limitedArray, MAX_SIZE, (byte) 0);
            } else if (componentType.equals(short.class)) {
                Array.setShort(limitedArray, MAX_SIZE, (short) 0);
            }
            return limitedArray;
        }
        return originalArray;
    }
}
