package io.bitdive.parent.trasirovka.agent.utils;


import com.dslplatform.json.DslJson;
import com.dslplatform.json.JsonWriter;
import io.bitdive.parent.anotations.NotMonitoringParamsClass;
import io.bitdive.parent.parserConfig.YamlParserConfig;
import lombok.Builder;
import lombok.Getter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public class JsonSerializer {

    @FunctionalInterface
    interface FieldSerializer {
        void serialize(Object obj, JsonWriter writer, Set<Object> visited, int depth) throws IOException;
    }

    @Getter
    @Builder
    public static class ClassCacheData {
        private Field[] fields;
        private boolean excludedPackage;
        private boolean notMonitoring;
        private boolean hibernateProxy;
        private boolean persistentCollection;
        private boolean lambdaOrFunctional;
        private List<FieldSerializer> fieldSerializers;
    }

    private static final DslJson<Object> DSL_JSON = new DslJson<>(new DslJson.Settings<>().includeServiceLoader());

    // Change to ConcurrentHashMap for thread safety
    private static final Map<Class<?>, ClassCacheData> FIELDS_CACHE = new ConcurrentHashMap<>();

    // ThreadLocal for visited sets to avoid constant reallocation
    private static final ThreadLocal<Set<Object>> VISITED_SETS = ThreadLocal.withInitial(
            () -> Collections.newSetFromMap(new IdentityHashMap<>())
    );

    private static final int MAX_DEPTH = 20;
    private static final int MAX_COLLECTION_SIZE = YamlParserConfig
            .getProfilingConfig().getMonitoring().getSerialization().getMaxElementCollection();

    private static final String[] EXCLUDED_PACKAGES = YamlParserConfig
            .getProfilingConfig().getMonitoring().getSerialization().getExcludedPackages();
    private static final String INDICATOR = "...";

    public static final Set<String> SENSITIVE_KEYWORDS = new HashSet<>(Arrays.asList(
            "password", "pass", "secret", "token", "key", "apikey", "auth", "credential"
    ));
    
    // Pattern for faster sensitive field detection
    private static final Pattern SENSITIVE_PATTERN = Pattern.compile(
            "(?i).*(password|pass|secret|token|key|apikey|auth|credential).*"
    );
    
    private static final Set<String> JAVA_STANDARD_PACKAGES = new HashSet<>(Arrays.asList(
            "java.", "javax.", "jdk.", "sun.", "com.sun."
    ));

    public static String serialize(Object obj) {
        if (obj == null) {
            return "null";
        }
        JsonWriter writer = DSL_JSON.newWriter();
        Set<Object> visited = VISITED_SETS.get();
        visited.clear(); // Reuse the set instead of creating a new one
        try {
            serializeValue(obj, writer, visited, 0);
            return writer.toString();
        } catch (Throwable t) {
            return "\"[Error: " + t.getMessage() + "]\"";
        } finally {
            visited.clear(); // Clean up after use
        }
    }

    public static void serialize(Object obj, ByteArrayOutputStream os) {
        if (obj == null) {
            try {
                os.write("null".getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                // ignored
            }
            return;
        }
        JsonWriter writer = DSL_JSON.newWriter();
        Set<Object> visited = VISITED_SETS.get();
        visited.clear(); // Reuse the set
        try {
            serializeValue(obj, writer, visited, 0);
            writer.toStream(os);
        } catch (IOException e) {
            // handle or log
        } catch (Throwable t) {
            // handle or log
        } finally {
            visited.clear(); // Clean up
        }
    }

    private static boolean isJavaStandardClass(Class<?> clazz) {
        String name = clazz.getName();
        for (String prefix : JAVA_STANDARD_PACKAGES) {
            if (name.startsWith(prefix)) return true;
        }
        return false;
    }

    private static void serializeValue(Object value,
                                       JsonWriter writer,
                                       Set<Object> visited,
                                       int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            writer.writeString("[MAX_DEPTH_REACHED]");
            return;
        }
        if (value == null) {
            writer.writeNull();
            return;
        }

        Class<?> clazz = value.getClass();

        // Handle common types quickly with specialized handlers
        if (handleSpecialTypes(value, writer)) {
            return;
        }

        // Fast-path for JDK collection types to avoid reflection
        if (value instanceof Collection) {
            serializeCollection((Collection<?>) value, writer, visited, depth);
            return;
        }
        
        if (clazz.isArray()) {
            serializeArray(value, writer, visited, depth);
            return;
        }
        
        if (value instanceof Map) {
            serializeMap((Map<?, ?>) value, writer, visited, depth);
            return;
        }

        // For Java standard library classes, just use toString rather than reflection
        if (isJavaStandardClass(clazz)) {
            writer.writeString(value.toString());
            return;
        }

        ClassCacheData classCacheData = FIELDS_CACHE.computeIfAbsent(clazz, JsonSerializer::getAllFields);

        // Лямбда / функциональный
        if (classCacheData.isLambdaOrFunctional()) {
            Class<?>[] interfaces = clazz.getInterfaces();
            if (interfaces.length == 1) {
                writer.writeString(interfaces[0].getName());
            } else {
                writer.writeString("lambda");
            }
            return;
        }

        // Исключённый пакет / аннотация
        if (classCacheData.isExcludedPackage() || classCacheData.isNotMonitoring()) {
            String str;
            try {
                str = value.toString();
            } catch (Exception e) {
                str = "[excluded]";
            }
            writer.writeString(str);
            return;
        }

        // Hibernate Proxy / PersistentCollection
        if (classCacheData.isHibernateProxy() || classCacheData.isPersistentCollection()) {
            writer.writeNull();
            return;
        }

        // Циклическая ссылка
        if (visited.contains(value)) {
            writer.writeString("[CIRCULAR_REFERENCE]");
            return;
        }
        visited.add(value);

        try {
            // Примитив
            if (clazz.isPrimitive()) {
                writePrimitive(value, writer);
                return;
            }
            // Number
            if (value instanceof Number) {
                writeNumber((Number) value, writer);
                return;
            }
            // Boolean
            if (value instanceof Boolean) {
                writeBoolean((Boolean) value, writer);
                return;
            }
            // String / Character
            if (value instanceof String) {
                writer.writeString((String) value);
                return;
            }
            if (value instanceof Character) {
                writer.writeString(value.toString());
                return;
            }
            // Enum
            if (value instanceof Enum<?>) {
                writer.writeString(((Enum<?>) value).name());
                return;
            }
            // byte[]
            if (value instanceof byte[]) {
                writer.writeString("byte_array");
                return;
            }
            
            // Обычный объект
            serializeObject(value, writer, visited, depth, classCacheData);

        } finally {
            // Если хотим при повторном появлении того же объекта возвращать [CIRCULAR_REFERENCE],
            // можем НЕ делать remove(...).
            visited.remove(value);
        }
    }

    // New method to handle special types with optimized serialization
    private static boolean handleSpecialTypes(Object value, JsonWriter writer) throws IOException {
        if (value instanceof UUID) {
            writer.writeString(value.toString());
            return true;
        }
        if (value instanceof LocalDate) {
            writer.writeString(((LocalDate) value).format(DateTimeFormatter.ISO_LOCAL_DATE));
            return true;
        }
        if (value instanceof LocalDateTime) {
            writer.writeString(((LocalDateTime) value).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            return true;
        }
        if (value instanceof Optional) {
            Optional<?> opt = (Optional<?>) value;
            if (opt.isPresent()) {
                serializeValue(opt.get(), writer, VISITED_SETS.get(), 0);
            } else {
                writer.writeNull();
            }
            return true;
        }
        return false;
    }

    // ========== Коллекции, массивы, мапы, объекты ==========

    private static void serializeCollection(Collection<?> collection,
                                            JsonWriter writer,
                                            Set<Object> visited,
                                            int depth) throws IOException {
        writer.writeByte(JsonWriter.ARRAY_START); // '['
        boolean first = true;
        
        // Fast path for empty collections
        if (collection.isEmpty()) {
            writer.writeByte(JsonWriter.ARRAY_END); // ']'
            return;
        }
        
        // Optimized iteration
        int maxSize = Math.min(collection.size(), MAX_COLLECTION_SIZE);
        Iterator<?> iterator = collection.iterator();
        
        for (int i = 0; i < maxSize; i++) {
            if (!first) {
                writer.writeByte(JsonWriter.COMMA);
            }
            serializeValue(iterator.next(), writer, visited, depth + 1);
            first = false;
        }
        
        if (collection.size() > MAX_COLLECTION_SIZE) {
            writer.writeByte(JsonWriter.COMMA);
            writer.writeString(INDICATOR);
        }
        
        writer.writeByte(JsonWriter.ARRAY_END); // ']'
    }

    private static void serializeArray(Object array,
                                       JsonWriter writer,
                                       Set<Object> visited,
                                       int depth) throws IOException {
        writer.writeByte(JsonWriter.ARRAY_START);
        int length = Array.getLength(array);
        
        // Fast path for empty arrays
        if (length == 0) {
            writer.writeByte(JsonWriter.ARRAY_END);
            return;
        }
        
        int maxElements = Math.min(length, MAX_COLLECTION_SIZE);
        boolean first = true;
        for (int i = 0; i < maxElements; i++) {
            if (!first) {
                writer.writeByte(JsonWriter.COMMA);
            }
            serializeValue(Array.get(array, i), writer, visited, depth + 1);
            first = false;
        }
        if (length > MAX_COLLECTION_SIZE) {
            if (!first) {
                writer.writeByte(JsonWriter.COMMA);
            }
            writer.writeString(INDICATOR);
        }
        writer.writeByte(JsonWriter.ARRAY_END);
    }

    private static void serializeMap(Map<?, ?> map,
                                     JsonWriter writer,
                                     Set<Object> visited,
                                     int depth) throws IOException {
        writer.writeByte(JsonWriter.OBJECT_START); // '{'
        
        // Fast path for empty maps
        if (map.isEmpty()) {
            writer.writeByte(JsonWriter.OBJECT_END); // '}'
            return;
        }
        
        int count = 0;
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (count >= MAX_COLLECTION_SIZE) {
                if (!first) {
                    writer.writeByte(JsonWriter.COMMA);
                }
                writer.writeString(INDICATOR);
                writer.writeByte(JsonWriter.SEMI); // ':'
                writer.writeString(INDICATOR);
                break;
            }
            if (!first) {
                writer.writeByte(JsonWriter.COMMA);
            }
            String key = String.valueOf(entry.getKey());
            writer.writeString(key);
            writer.writeByte(JsonWriter.SEMI); // ':'
            if (isSensitiveField(key)) {
                writer.writeString("******");
            } else {
                serializeValue(entry.getValue(), writer, visited, depth + 1);
            }
            count++;
            first = false;
        }
        writer.writeByte(JsonWriter.OBJECT_END); // '}'
    }

    private static void serializeObject(Object obj,
                                        JsonWriter writer,
                                        Set<Object> visited,
                                        int depth,
                                        ClassCacheData classCacheData) throws IOException {

        if (classCacheData.isHibernateProxy()) {
            writer.writeString("[HIBERNATE_PROXY]");
            return;
        }

        writer.writeByte(JsonWriter.OBJECT_START); // '{'

        List<FieldSerializer> serializers = classCacheData.getFieldSerializers();
        if (serializers.isEmpty()) {
            writer.writeByte(JsonWriter.OBJECT_END); // '}'
            return;
        }

        boolean first = true;
        for (FieldSerializer serializer : serializers) {
            if (!first) {
                writer.writeByte(JsonWriter.COMMA);
            }
            try {
                serializer.serialize(obj, writer, visited, depth);
                first = false;
            } catch (Exception e) {
                // Skip fields that can't be accessed due to module restrictions
                // This prevents errors like the ArrayList.elementData access issue
            }
        }

        writer.writeByte(JsonWriter.OBJECT_END); // '}'
    }

    private static List<FieldSerializer> generateSerializers(Class<?> clazz) {
        List<FieldSerializer> serializers = new ArrayList<>();
        Class<?> current = clazz;

        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                int mods = field.getModifiers();
                if (Modifier.isStatic(mods) && Modifier.isFinal(mods)) continue;
                
                try {
                    field.setAccessible(true);
                } catch (Exception e) {
                    // Skip fields that can't be made accessible
                    continue;
                }
                
                // Cache the field name and check if it's sensitive
                final String fieldName = field.getName();
                final boolean isSensitive = isSensitiveField(fieldName);
                final Field finalField = field;
                
                serializers.add((obj, writer, visited, depth) -> {
                    writer.writeString(fieldName);
                    writer.writeByte(JsonWriter.SEMI); // ':'
                    
                    if (isSensitive) {
                        writer.writeString("******");
                        return;
                    }
                    
                    try {
                        Object fieldValue = finalField.get(obj);
                        serializeValue(fieldValue, writer, visited, depth + 1);
                    } catch (IllegalAccessException | SecurityException ignored) {
                        writer.writeNull();
                    }
                });
            }
            current = current.getSuperclass();
        }

        return serializers;
    }
    // ========== Запись примитивов и чисел (без writeBoolean, writeInt и т.д.) ==========


    private static void writePrimitive(Object value, JsonWriter writer) {
        // value - это точно примитивный тип: int, long, boolean, byte, short, float, double, char
        // Но если это char — мы выше уже обрабатываем Character.
        // Так что здесь: boolean/int/long/float/double/byte/short.

        // Можно определить конкретно:
        String str = String.valueOf(value);
        // т.к. DSL-JSON не даёт публичных writeBoolean(...), writeInt(...):
        // пишем "true"/"false" или число как ASCII
        writer.writeAscii(str);
    }

    private static void writeNumber(Number number, JsonWriter writer) {
        // Это обёртки (Integer, Long, Double, BigDecimal и т.д.)
        // Просто переводим в String
        String str = number.toString();
        writer.writeAscii(str);
    }

    private static void writeBoolean(Boolean bool, JsonWriter writer) {
        // Либо напрямую "true"/"false"
        writer.writeAscii(bool ? "true" : "false");
    }

    // ========== Вспомогательные методы ==========

    private static ClassCacheData getAllFields(Class<?> clazz) {
        List<Field> result = new ArrayList<>();
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            Field[] declared = current.getDeclaredFields();
            for (Field f : declared) {
                int mods = f.getModifiers();
                if (Modifier.isStatic(mods) && Modifier.isFinal(mods)) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    result.add(f);
                } catch (Exception e) {
                    // Skip fields that can't be made accessible
                }
            }
            current = current.getSuperclass();
        }


        return ClassCacheData.builder()
                .excludedPackage(isExcludedPackage1(clazz))
                .hibernateProxy(isHibernateProxy1(clazz))
                .lambdaOrFunctional(isLambdaOrFunctionalInterface1(clazz))
                .notMonitoring(hasNotMonitoringAnnotation1(clazz))
                .persistentCollection(isPersistentCollection1(clazz))
                .fields(result.toArray(new Field[0]))
                .fieldSerializers(generateSerializers(clazz))
                .build();
    }

    private static boolean isExcludedPackage1(Class<?> clazz) {
        String className = clazz.getName();
        Class<?>[] interfaces = clazz.getInterfaces();
        for (String pkg : EXCLUDED_PACKAGES) {
            if (className.startsWith(pkg)) {
                return true;
            }
            for (Class<?> iface : interfaces) {
                if (iface.getName().startsWith(pkg)) {
                    return true;
                }
            }
        }
        return false;

    }

    private static boolean hasNotMonitoringAnnotation1(Class<?> clazz) {
        return clazz.isAnnotationPresent(NotMonitoringParamsClass.class);
    }

    private static boolean isHibernateProxy1(Class<?> clazz) {
        return  isClassInHierarchy(clazz, "org.hibernate.proxy.HibernateProxy");
    }

    private static boolean isPersistentCollection1(Class<?> clazz) {
        return isClassInHierarchy(clazz, "org.hibernate.collection.spi.PersistentCollection");
    }

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

    private static boolean isLambdaOrFunctionalInterface1(Class<?> clazz) {
        if (clazz.isSynthetic() && clazz.getName().contains("$$Lambda$")) {
            return true;
        }
        if (clazz.isAnonymousClass()) {
            Class<?>[] interfaces = clazz.getInterfaces();
            return interfaces.length == 1 && isTrulyFunctionalInterface(interfaces[0]);
        }
        return false;
    }

    private static boolean isTrulyFunctionalInterface(Class<?> iface) {
        if (iface.isAnnotationPresent(FunctionalInterface.class)) {
            return true;
        }
        int abstractMethodCount = 0;
        for (java.lang.reflect.Method m : iface.getMethods()) {
            if (m.getDeclaringClass() == Object.class) {
                continue;
            }
            if (Modifier.isAbstract(m.getModifiers())) {
                abstractMethodCount++;
                if (abstractMethodCount > 1) {
                    return false;
                }
            }
        }
        return abstractMethodCount == 1;
    }

    // Optimized sensitive field checking using regex pattern
    private static boolean isSensitiveField(String fieldName) {
        return SENSITIVE_PATTERN.matcher(fieldName).matches();
    }
} 