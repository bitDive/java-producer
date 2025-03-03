package io.bitdive.parent.trasirovka.agent.utils;

import io.bitdive.parent.anotations.NotMonitoringParamsClass;
import io.bitdive.parent.parserConfig.YamlParserConfig;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class JsonSerializer {

    // Кэш полей (для каждого класса хранится массив Field[])
    private static final Map<Class<?>, Field[]> FIELDS_CACHE = new ConcurrentHashMap<>();

    // Кэши для проверок (чтобы каждый раз не делать глубокий анализ)
    private static final Map<Class<?>, Boolean> EXCLUDED_PACKAGE_CACHE = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Boolean> NOT_MONITORING_CACHE = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Boolean> HIBERNATE_PROXY_CACHE = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Boolean> PERSISTENT_COLLECTION_CACHE = new ConcurrentHashMap<>();

    private static final int MAX_DEPTH = 10;

    // Настройки из конфигурации
    private static final int MAX_COLLECTION_SIZE = YamlParserConfig
            .getProfilingConfig().getMonitoring().getSerialization().getMaxElementCollection();
    private static final String[] EXCLUDED_PACKAGES = YamlParserConfig
            .getProfilingConfig().getMonitoring().getSerialization().getExcludedPackages();

    private static final String INDICATOR = "...";

    public static final Set<String> SENSITIVE_KEYWORDS = new HashSet<>(Arrays.asList(
            "password", "pass", "secret", "token", "key", "apikey", "auth", "credential"
    ));

    public static String serialize(Object obj) {
        if (obj == null) {
            return "null";
        }
        // Используем IdentityHashMap для отслеживания ссылок (цикл/повтор)
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        StringBuilder sb = new StringBuilder();
        try {
            serializeValue(obj, sb, visited, 0);
        } catch (Exception e) {
            return "\"[Error: " + e.getMessage() + "]\"";
        } catch (Throwable t) {
            return "\"Error converting data\"";
        }
        return sb.toString();
    }

    /**
     * Основной метод, который последовательно обрабатывает все типы
     * (примитивы, строки, коллекции, массивы, мапы, объекты).
     */
    private static void serializeValue(Object value,
                                       StringBuilder sb,
                                       Set<Object> visited,
                                       int depth) {
        if (depth > MAX_DEPTH) {
            sb.append("\"[MAX_DEPTH_REACHED]\"");
            return;
        }
        if (value == null) {
            sb.append("null");
            return;
        }

        Class<?> clazz = value.getClass();

        // 1. Быстрая проверка: исключён ли пакет? есть ли аннотация @NotMonitoringParamsClass?
        if (isExcludedPackage(clazz) || hasNotMonitoringAnnotation(clazz)) {
            // Если исключён, просто выводим либо value.toString(), либо "[excluded]"
            try {
                sb.append("\"").append(value.toString()).append("\"");
            } catch (Exception e) {
                sb.append("\"[excluded]\"");
            }
            return;
        }

        // 2. Проверка на Hibernate Proxy / PersistentCollection
        if (isHibernateProxy(clazz) || isPersistentCollection(clazz)) {
            sb.append("null");
            return;
        }

        // 3. Защита от циклов по идентичности
        if (visited.contains(value)) {
            sb.append("\"[CIRCULAR_REFERENCE]\"");
            return;
        }
        visited.add(value);

        try {
            // 4. Переключаемся по типам
            if (clazz.isPrimitive() || value instanceof Number || value instanceof Boolean) {
                sb.append(value);
            } else if (value instanceof String || value instanceof Character) {
                serializeString(value.toString(), sb);
            } else if (value instanceof Enum<?>) {
                serializeString(((Enum<?>) value).name(), sb);
            } else if (value instanceof byte[]) {
                // Не раскодируем, а просто пишем
                sb.append("\"byte_array\"");
            } else if (value instanceof Collection) {
                serializeCollection((Collection<?>) value, sb, visited, depth);
            } else if (clazz.isArray()) {
                // Массив
                if (clazz.getComponentType().isPrimitive()) {
                    serializePrimitiveArray(value, sb);
                } else {
                    serializeArray(value, sb, visited, depth);
                }
            } else if (value instanceof Map) {
                serializeMap((Map<?, ?>) value, sb, visited, depth);
            } else {
                // Сериализация объекта (через поля)
                serializeObject(value, sb, visited, depth);
            }
        } finally {
            // Если хотим, чтобы при повторной встрече того же объекта
            // (в другой ветке графа) мы снова его сериализовали — убираем из visited:
            visited.remove(value);

            // Если же мы хотим один раз сериализовать, а дальше при повторе
            // всегда выдавать "[CIRCULAR_REFERENCE]" — то этот remove можно не делать.
        }
    }

    private static void serializeCollection(Collection<?> collection,
                                            StringBuilder sb,
                                            Set<Object> visited,
                                            int depth) {
        sb.append('[');
        int count = 0;
        for (Object item : collection) {
            if (count >= MAX_COLLECTION_SIZE) {
                sb.append(',');
                serializeString(INDICATOR, sb);
                break;
            }
            if (count > 0) {
                sb.append(',');
            }
            serializeValue(item, sb, visited, depth + 1);
            count++;
        }
        sb.append(']');
    }

    private static void serializeArray(Object array,
                                       StringBuilder sb,
                                       Set<Object> visited,
                                       int depth) {
        sb.append('[');
        int length = Array.getLength(array);
        int maxElements = Math.min(length, MAX_COLLECTION_SIZE);

        for (int i = 0; i < maxElements; i++) {
            if (i > 0) {
                sb.append(',');
            }
            Object element = Array.get(array, i);
            serializeValue(element, sb, visited, depth + 1);
        }
        if (length > MAX_COLLECTION_SIZE) {
            sb.append(',');
            serializeString(INDICATOR, sb);
        }
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
            sb.append(Array.get(array, i));
        }
        if (length > MAX_COLLECTION_SIZE) {
            sb.append(',');
            serializeString(INDICATOR, sb);
        }
        sb.append(']');
    }

    private static void serializeMap(Map<?, ?> map,
                                     StringBuilder sb,
                                     Set<Object> visited,
                                     int depth) {
        sb.append('{');
        int count = 0;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (count >= MAX_COLLECTION_SIZE) {
                sb.append(',');
                serializeString(INDICATOR, sb);
                sb.append(':');
                serializeString(INDICATOR, sb);
                break;
            }
            if (count > 0) {
                sb.append(',');
            }

            // Ключ может быть не строкой (Integer и т.д.),
            // но для JSON ключ всегда строка, поэтому:
            String key = String.valueOf(entry.getKey());
            serializeString(key, sb);
            sb.append(':');

            if (isSensitiveField(key)) {
                // Если ключ "password" или "secret" и т.п.
                sb.append("\"******\"");
            } else {
                serializeValue(entry.getValue(), sb, visited, depth + 1);
            }
            count++;
        }
        sb.append('}');
    }

    /**
     * Рефлексивная сериализация объекта по его полям (кроме static final и т.д.).
     */
    private static void serializeObject(Object obj,
                                        StringBuilder sb,
                                        Set<Object> visited,
                                        int depth) {
        Class<?> clazz = obj.getClass();

        // Дополнительная проверка, если вдруг опять оказался Hibernate Proxy:
        if (isHibernateProxy(clazz)) {
            sb.append("\"[HIBERNATE_PROXY]\"");
            return;
        }

        Field[] fields = FIELDS_CACHE.computeIfAbsent(clazz, JsonSerializer::getAllFields);

        sb.append('{');
        boolean first = true;
        for (Field field : fields) {
            try {
                Object fieldValue = field.get(obj);
                if (!first) {
                    sb.append(',');
                }
                serializeString(field.getName(), sb);
                sb.append(':');
                serializeValue(fieldValue, sb, visited, depth + 1);
                first = false;
            } catch (IllegalAccessException e) {
                // Если доступ закрыт — пропускаем
            }
        }
        sb.append('}');
    }

    /**
     * Экранируем строку по правилам JSON (кавычки, слеши, управляющие символы).
     */
    private static void serializeString(String str, StringBuilder sb) {
        sb.append('"');
        final int length = str.length();
        for (int i = 0; i < length; i++) {
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

    /**
     * Проверяем, не содержит ли название поля чувствительные слова (token, secret, password...).
     */
    private static boolean isSensitiveField(String fieldName) {
        String lower = fieldName.toLowerCase();
        for (String keyword : SENSITIVE_KEYWORDS) {
            if (lower.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Получаем все поля класса (включая иерархию), пропуская static final и т.п.,
     * и делаем setAccessible(true), если возможно.
     */
    private static Field[] getAllFields(Class<?> clazz) {
        List<Field> result = new ArrayList<>();
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            Field[] declared = current.getDeclaredFields();
            for (Field f : declared) {
                int mods = f.getModifiers();
                // Пропустим static final, так как они обычно содержат константы
                // (serialVersionUID и т.д.), которые не нужны для сериализации
                if (Modifier.isStatic(mods) && Modifier.isFinal(mods)) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                } catch (Exception e) {
                    // Если нельзя открыть доступ — пропустим
                }
                result.add(f);
            }
            current = current.getSuperclass();
        }
        return result.toArray(new Field[0]);
    }

    // ===================== Логика проверки исключений и кэши =====================

    private static boolean isExcludedPackage(Class<?> clazz) {
        return EXCLUDED_PACKAGE_CACHE.computeIfAbsent(clazz, c -> {
            String className = c.getName();
            Class<?>[] interfaces = c.getInterfaces();

            for (String pkg : EXCLUDED_PACKAGES) {
                if (className.startsWith(pkg)) {
                    return true;
                }
                // Также проверяем интерфейсы
                for (Class<?> iface : interfaces) {
                    if (iface.getName().startsWith(pkg)) {
                        return true;
                    }
                }
            }
            return false;
        });
    }

    private static boolean hasNotMonitoringAnnotation(Class<?> clazz) {
        return NOT_MONITORING_CACHE.computeIfAbsent(clazz, c ->
                c.isAnnotationPresent(NotMonitoringParamsClass.class)
        );
    }

    private static boolean isHibernateProxy(Class<?> clazz) {
        return HIBERNATE_PROXY_CACHE.computeIfAbsent(clazz, c ->
                isClassInHierarchy(c, "org.hibernate.proxy.HibernateProxy")
        );
    }

    private static boolean isPersistentCollection(Class<?> clazz) {
        return PERSISTENT_COLLECTION_CACHE.computeIfAbsent(clazz, c ->
                isClassInHierarchy(c, "org.hibernate.collection.spi.PersistentCollection")
        );
    }

    /**
     * Рекурсивный проход по иерархии (и интерфейсам), чтобы найти нужное имя класса.
     */
    private static boolean isClassInHierarchy(Class<?> clazz, String targetClassName) {
        while (clazz != null) {
            if (clazz.getName().equals(targetClassName)) {
                return true;
            }
            for (Class<?> iface : clazz.getInterfaces()) {
                if (iface.getName().equals(targetClassName)
                        || isClassInHierarchy(iface, targetClassName)) {
                    return true;
                }
            }
            clazz = clazz.getSuperclass();
        }
        return false;
    }
}
