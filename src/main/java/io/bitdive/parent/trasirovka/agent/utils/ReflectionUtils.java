package io.bitdive.parent.trasirovka.agent.utils;

import java.lang.reflect.Field;
import java.lang.reflect.InaccessibleObjectException;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

public class ReflectionUtils {

    private static final Pattern DEFAULT_TOSTRING_PATTERN = Pattern.compile("^(?:\\w+\\.)+\\w+@\\w+$");


    public static String objectToString(Object obj) {
        return objectToString(obj, new StringBuilder(), 0).toString();
    }

    private static StringBuilder objectToString(Object obj, StringBuilder sb, int indent) {
        if (obj == null) {
            sb.append("null");
            return sb;
        }

        Class<?> clazz = obj.getClass();

        // Проверяем, является ли объект стандартным Java-классом
        if (isStandardJavaClass(clazz)) {
            sb.append(obj.toString());
            return sb;
        }

        // Обрабатываем CompletableFuture отдельно
        if (obj instanceof CompletableFuture) {
            CompletableFuture<?> future = (CompletableFuture<?>) obj;
            sb.append("CompletableFuture {");
            if (future.isDone()) {
                Object result = future.getNow(null);
                sb.append("\n");
                for (int i = 0; i <= indent; i++) {
                    sb.append("  ");
                }
                sb.append("result = ");
                objectToString(result, sb, indent + 1);
            } else {
                sb.append("\n");
                for (int i = 0; i <= indent; i++) {
                    sb.append("  ");
                }
                sb.append("status = not completed");
            }
            sb.append("\n");
            for (int i = 0; i < indent; i++) {
                sb.append("  ");
            }
            sb.append("}");
            return sb;
        }

        // Обрабатываем коллекции
        if (obj instanceof Collection) {
            Collection<?> collection = (Collection<?>) obj;
            sb.append("[");
            for (Object item : collection) {
                objectToString(item, sb, indent + 1);
                sb.append(", ");
            }
            if (!collection.isEmpty()) {
                sb.setLength(sb.length() - 2); // Удаляем лишнюю запятую и пробел
            }
            sb.append("]");
            return sb;
        }

        // Обрабатываем карты
        if (obj instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) obj;
            sb.append("{");
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                objectToString(entry.getKey(), sb, indent + 1);
                sb.append("=");
                objectToString(entry.getValue(), sb, indent + 1);
                sb.append(", ");
            }
            if (!map.isEmpty()) {
                sb.setLength(sb.length() - 2); // Удаляем лишнюю запятую и пробел
            }
            sb.append("}");
            return sb;
        }

        // Обрабатываем массивы
        if (clazz.isArray()) {
            sb.append("[");
            int length = java.lang.reflect.Array.getLength(obj);
            for (int i = 0; i < length; i++) {
                Object item = java.lang.reflect.Array.get(obj, i);
                objectToString(item, sb, indent + 1);
                sb.append(", ");
            }
            if (length > 0) {
                sb.setLength(sb.length() - 2); // Удаляем лишнюю запятую и пробел
            }
            sb.append("]");
            return sb;
        }

        // Пытаемся использовать метод toString()
        String toStringResult = obj.toString();

        if (isDefaultToString(toStringResult)) {
            // Если это стандартная реализация toString(), используем рефлексию
            sb.append(clazz.getSimpleName()).append(" {");
            Field[] fields = getAllFields(clazz);

            for (Field field : fields) {
                try {
                    field.setAccessible(true);
                    Object value = field.get(obj);
                    if (value != null) {
                        // Проверяем, не является ли поле наследованным от Object
                        if (field.getDeclaringClass() != Object.class) {
                            sb.append("\n");
                            for (int i = 0; i <= indent; i++) {
                                sb.append("  ");
                            }
                            sb.append(field.getName()).append(" = ");
                            objectToString(value, sb, indent + 1);
                        }
                    }
                } catch (IllegalAccessException | InaccessibleObjectException e) {
                    // Пропускаем поле, к которому нет доступа
                    continue;
                }
            }
            sb.append("\n");
            for (int i = 0; i < indent; i++) {
                sb.append("  ");
            }
            sb.append("}");
        } else {
            // Иначе используем результат toString()
            sb.append(toStringResult);
        }

        return sb;
    }

    // Метод для проверки, является ли результат toString() стандартной реализацией Object.toString()
    private static boolean isDefaultToString(String toStringResult) {
        return DEFAULT_TOSTRING_PATTERN.matcher(toStringResult).matches();
    }

    // Метод для получения всех полей класса, включая наследованные, но исключая поля из Object
    private static Field[] getAllFields(Class<?> clazz) {
        if (clazz == Object.class) {
            return new Field[0];
        }
        Field[] fields = clazz.getDeclaredFields();
        Field[] parentFields = getAllFields(clazz.getSuperclass());
        Field[] allFields = new Field[fields.length + parentFields.length];
        System.arraycopy(fields, 0, allFields, 0, fields.length);
        System.arraycopy(parentFields, 0, allFields, fields.length, parentFields.length);
        return allFields;
    }

    // Метод для проверки, является ли класс стандартным классом Java
    private static boolean isStandardJavaClass(Class<?> clazz) {
        String className = clazz.getName();
        return clazz.isPrimitive()
                || (className.startsWith("java.") && !className.startsWith("java.util.concurrent."))
                || className.startsWith("javax.")
                || clazz == String.class
                || Number.class.isAssignableFrom(clazz)
                || clazz == Boolean.class
                || clazz == Character.class
                || clazz == Void.class;
    }
}
