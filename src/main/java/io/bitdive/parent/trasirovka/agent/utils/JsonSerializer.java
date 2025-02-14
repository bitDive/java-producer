package io.bitdive.parent.trasirovka.agent.utils;

import io.bitdive.parent.anotations.NotMonitoringParamsClass;
import io.bitdive.parent.parserConfig.YamlParserConfig;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class JsonSerializer {
    private static final Map<Class<?>, Field[]> FIELDS_CACHE = new ConcurrentHashMap<>();
    private static final int MAX_DEPTH = 10;
    private static final int MAX_COLLECTION_SIZE = YamlParserConfig.getProfilingConfig().getMonitoring().getSerialization().getMaxElementCollection();
    private static final String INDICATOR = "...";

    private static final Set<String> SENSITIVE_KEYWORDS = new HashSet<>(Arrays.asList(
            "password", "pass", "secret", "token", "key", "apiKey", "auth", "credential"
    ));

    public static String serialize(Object obj) {
        try {
            if (obj == null) {
                return "null";
            }

            try {
                // Check for NotMonitoringParamsClass annotation
                if (obj.getClass().isAnnotationPresent(NotMonitoringParamsClass.class)) {
                    NotMonitoringParamsClass annotation = obj.getClass().getAnnotation(NotMonitoringParamsClass.class);
                    return "\"" + annotation.value() + "\"";
                }

                // Check excluded packages
                String[] excludedPackages = YamlParserConfig.getProfilingConfig().getMonitoring().getSerialization().getExcludedPackages();
                for (String pkg : excludedPackages) {
                    if (obj.getClass().getName().startsWith(pkg)) {
                        try {
                            return "\"" + obj.toString() + "\"";
                        } catch (Exception e) {
                            return "\"[Error in toString()]\"";
                        }
                    }
                }

                StringBuilder sb = new StringBuilder();
                Set<Object> visited = new HashSet<>();
                serializeValue(obj, sb, visited, 0);
                return sb.toString();
            } catch (Exception e) {
                return "\"[Error: " + e.getMessage() + "]\"";
            }
        } catch (Throwable t) {
            return "\"Error converting data\"";
        }
    }

    private static void serializeValue(Object value, StringBuilder sb, Set<Object> visited, int depth) {
        if (depth > MAX_DEPTH) {
            sb.append("\"[MAX_DEPTH_REACHED]\"");
            return;
        }

        if (value == null) {
            sb.append("null");
            return;
        }

        // Skip if it's a Hibernate proxy
        if (shouldSkipValue(value)) {
            sb.append("null");
            return;
        }

        Class<?> clazz = value.getClass();

        // Add special handling for enums
        if (value instanceof Enum<?>) {
            serializeString(((Enum<?>) value).name(), sb);
            return;
        }

        if (visited.contains(value)) {
            sb.append("\"[CIRCULAR_REFERENCE]\"");
            return;
        }

        if (value instanceof byte[]) {
            sb.append("\"byte_array\"");
            return;
        }

        if (clazz.isPrimitive() || value instanceof Number || value instanceof Boolean) {
            sb.append(value);
        } else if (value instanceof String || value instanceof Character) {
            serializeString(value.toString(), sb);
        } else if (value instanceof Collection) {
            serializeCollection((Collection<?>) value, sb, visited, depth);
        } else if (clazz.isArray()) {
            if (clazz.getComponentType().isPrimitive()) {
                serializePrimitiveArray(value, sb);
            } else {
                serializeArray(value, sb, visited, depth);
            }
        } else if (value instanceof Map) {
            serializeMap((Map<?, ?>) value, sb, visited, depth);
        } else {
            serializeObject(value, sb, visited, depth);
        }
    }

    private static boolean shouldSkipValue(Object value) {
        if (value == null) return true;

        Class<?> clazz = value.getClass();
        return isHibernateProxy(clazz) || isPersistentCollection(clazz);
    }

    private static boolean isHibernateProxy(Class<?> clazz) {
        return isClassInHierarchy(clazz, "org.hibernate.proxy.HibernateProxy");
    }

    private static boolean isPersistentCollection(Class<?> clazz) {
        return isClassInHierarchy(clazz, "org.hibernate.collection.spi.PersistentCollection");
    }

    private static boolean isClassInHierarchy(Class<?> clazz, String targetClassName) {
        while (clazz != null) {
            if (clazz.getName().equals(targetClassName)) {
                return true;
            }
            // Check interfaces
            for (Class<?> iface : clazz.getInterfaces()) {
                if (iface.getName().equals(targetClassName) || isClassInHierarchy(iface, targetClassName)) {
                    return true;
                }
            }
            clazz = clazz.getSuperclass();
        }
        return false;
    }

    private static void serializeString(String str, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < ' ') {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    private static void serializeCollection(Collection<?> collection, StringBuilder sb, Set<Object> visited, int depth) {
        sb.append('[');
        boolean first = true;
        visited.add(collection);

        int count = 0;
        for (Object item : collection) {
            if (count >= MAX_COLLECTION_SIZE) {
                if (!first) sb.append(',');
                serializeString(INDICATOR, sb);
                break;
            }
            if (!first) {
                sb.append(',');
            }
            serializeValue(item, sb, visited, depth + 1);
            first = false;
            count++;
        }

        visited.remove(collection);
        sb.append(']');
    }

    private static void serializeArray(Object array, StringBuilder sb, Set<Object> visited, int depth) {
        sb.append('[');
        int length = Array.getLength(array);
        visited.add(array);

        int maxElements = Math.min(length, MAX_COLLECTION_SIZE);
        for (int i = 0; i < maxElements; i++) {
            if (i > 0) {
                sb.append(',');
            }
            serializeValue(Array.get(array, i), sb, visited, depth + 1);
        }

        if (length > MAX_COLLECTION_SIZE) {
            sb.append(',');
            serializeString(INDICATOR, sb);
        }

        visited.remove(array);
        sb.append(']');
    }

    private static void serializePrimitiveArray(Object array, StringBuilder sb) {
        sb.append('[');
        int length = Array.getLength(array);
        int maxElements = Math.min(length, MAX_COLLECTION_SIZE);

        for (int i = 0; i < maxElements; i++) {
            if (i > 0) {
                sb.append(',');
            }
            Object element = Array.get(array, i);
            sb.append(element);
        }

        if (length > MAX_COLLECTION_SIZE) {
            sb.append(',');
            serializeString(INDICATOR, sb);
        }

        sb.append(']');
    }

    private static void serializeMap(Map<?, ?> map, StringBuilder sb, Set<Object> visited, int depth) {
        sb.append('{');
        boolean first = true;
        visited.add(map);

        int count = 0;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (count >= MAX_COLLECTION_SIZE) {
                if (!first) sb.append(',');
                serializeString(INDICATOR, sb);
                sb.append(':');
                serializeString(INDICATOR, sb);
                break;
            }
            if (!first) {
                sb.append(',');
            }
            String key = String.valueOf(entry.getKey());
            serializeString(key, sb);
            sb.append(':');
            if (isSensitiveField(key)) {
                sb.append("\"******\"");
            } else {
                serializeValue(entry.getValue(), sb, visited, depth + 1);
            }
            first = false;
            count++;
        }

        visited.remove(map);
        sb.append('}');
    }

    private static Field getField(Class<?> clazz, String fieldName) {
        // Search for the field in the class hierarchy
        while (clazz != null && clazz != Object.class) {
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

    private static void serializeObject(Object obj, StringBuilder sb, Set<Object> visited, int depth) {
        Class<?> clazz = obj.getClass();

        // Skip Hibernate proxies
        if (isHibernateProxy(clazz)) {
            sb.append("\"[HIBERNATE_PROXY]\"");
            return;
        }

        try {
            Field[] fields = FIELDS_CACHE.computeIfAbsent(clazz, JsonSerializer::getDeclaredFields);

            sb.append('{');
            boolean first = true;
            visited.add(obj);

            for (Field field : fields) {
                try {
                    Field actualField = getField(obj.getClass(), field.getName());
                    if (actualField == null) continue;

                    Object value = field.get(obj);
                    if (!first) {
                        sb.append(',');
                    }
                    serializeString(field.getName(), sb);
                    sb.append(':');
                    serializeValue(value, sb, visited, depth + 1);
                    first = false;
                } catch (IllegalAccessException e) {
                    // Skip inaccessible fields
                }
            }

            visited.remove(obj);
            sb.append('}');
        } catch (Exception e) {
            // If we can't access fields, fallback to toString()
            try {
                serializeString(obj.toString(), sb);
            } catch (Exception ex) {
                sb.append("\"[Object cannot be serialized]\"");
            }
        }
    }

    private static boolean isSensitiveField(String fieldName) {
        String lowerCaseField = fieldName.toLowerCase();
        return SENSITIVE_KEYWORDS.stream().anyMatch(keyword -> lowerCaseField.contains(keyword.toLowerCase()));
    }

    private static Field[] getDeclaredFields(Class<?> clazz) {
        List<Field> fieldList = new ArrayList<>();
        Class<?> currentClass = clazz;

        // Get fields from the entire class hierarchy
        while (currentClass != null && currentClass != Object.class) {
            for (Field field : currentClass.getDeclaredFields()) {
                field.setAccessible(true);
                fieldList.add(field);
            }
            currentClass = currentClass.getSuperclass();
        }

        return fieldList.toArray(new Field[0]);
    }
} 