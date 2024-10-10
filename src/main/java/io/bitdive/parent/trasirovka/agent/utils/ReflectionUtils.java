package io.bitdive.parent.trasirovka.agent.utils;

import io.bitdive.parent.anotations.NotMonitoringParamsClass;
import io.bitdive.parent.anotations.NotMonitoringParamsField;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

public class ReflectionUtils {
    private static final Pattern DEFAULT_TOSTRING_PATTERN = Pattern.compile("^(?:\\w+\\.)+\\w+@\\w+$");

    /**
     * Converts an object to a string representation.
     *
     * @param obj The object to serialize.
     * @return The string representation of the object.
     */
    public static String objectToString(Object obj) {
        return objectToString(obj, new StringBuilder(), 0, new IdentityHashMap<>()).toString();
    }

    private static StringBuilder objectToString(Object obj, StringBuilder sb, int indent, Map<Object, Boolean> visited) {
        if (obj == null) {
            sb.append("null");
            return sb;
        }

        Class<?> clazz = obj.getClass();

        NotMonitoringParamsClass classAnnotation = clazz.getAnnotation(NotMonitoringParamsClass.class);
        if (classAnnotation != null) {
            sb.append(classAnnotation.value());
            return sb;
        }

        if (isStandardJavaClass(clazz)) {
            sb.append(obj.toString());
            return sb;
        }

        if (visited.containsKey(obj)) {
            sb.append("[Cyclic reference detected]");
            return sb;
        }
        visited.put(obj, true);

        if (obj instanceof CompletableFuture) {
            CompletableFuture<?> future = (CompletableFuture<?>) obj;
            sb.append("CompletableFuture {");
            if (future.isDone()) {
                Object result = future.getNow(null);
                sb.append(" result = ");
                objectToString(result, sb, indent + 1, visited);
            } else {
                sb.append(" status = not completed");
            }
            sb.append(" }");
            return sb;
        }

        if (obj instanceof Collection) {
            Collection<?> collection = (Collection<?>) obj;
            sb.append("[");
            boolean first = true;
            for (Object item : collection) {
                if (!first) {
                    sb.append(", ");
                }
                objectToString(item, sb, indent + 1, visited);
                first = false;
            }
            sb.append("]");
            return sb;
        }

        if (obj instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) obj;
            sb.append("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    sb.append(", ");
                }
                objectToString(entry.getKey(), sb, indent + 1, visited);
                sb.append("=");
                objectToString(entry.getValue(), sb, indent + 1, visited);
                first = false;
            }
            sb.append("}");
            return sb;
        }

        if (clazz.isArray()) {
            sb.append("[");
            int length = java.lang.reflect.Array.getLength(obj);
            for (int i = 0; i < length; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                Object item = java.lang.reflect.Array.get(obj, i);
                objectToString(item, sb, indent + 1, visited);
            }
            sb.append("]");
            return sb;
        }


        String toStringResult = obj.toString();

        if (isDefaultToString(toStringResult)) {
            sb.append(clazz.getSimpleName()).append(" {");
            Field[] fields = getAllFields(clazz);
            boolean firstField = true;

            for (Field field : fields) {
                try {
                    field.setAccessible(true);
                    Object value = field.get(obj);

                    NotMonitoringParamsField fieldAnnotation = field.getAnnotation(NotMonitoringParamsField.class);
                    if (fieldAnnotation != null) {
                        value = fieldAnnotation.value();
                    }

                    if (value != null) {
                        if (field.getDeclaringClass() != Object.class) {
                            if (!firstField) {
                                sb.append(", ");
                            }
                            sb.append(field.getName()).append("=");
                            objectToString(value, sb, indent + 1, visited);
                            firstField = false;
                        }
                    }
                } catch (Exception e) {
                    continue;
                }
            }
            sb.append(" }");
        } else {
            sb.append(toStringResult);
        }

        return sb;
    }

    private static boolean isDefaultToString(String toStringResult) {
        return DEFAULT_TOSTRING_PATTERN.matcher(toStringResult).matches();
    }

    private static Field[] getAllFields(Class<?> clazz) {
        if (clazz == null || clazz == Object.class) {
            return new Field[0];
        }
        Field[] fields = clazz.getDeclaredFields();
        Field[] parentFields = getAllFields(clazz.getSuperclass());
        Field[] allFields = new Field[fields.length + parentFields.length];
        System.arraycopy(fields, 0, allFields, 0, fields.length);
        System.arraycopy(parentFields, 0, allFields, fields.length, parentFields.length);
        return allFields;
    }

    private static boolean isStandardJavaClass(Class<?> clazz) {
        if (clazz.isPrimitive()) {
            return true;
        }
        String className = clazz.getName();
        return className.startsWith("java.")
                || className.startsWith("javax.")
                || clazz == String.class
                || Number.class.isAssignableFrom(clazz)
                || clazz == Boolean.class
                || clazz == Character.class
                || clazz == Void.class;
    }
}
