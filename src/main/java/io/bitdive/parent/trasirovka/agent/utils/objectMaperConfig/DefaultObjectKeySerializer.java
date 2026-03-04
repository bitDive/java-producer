package io.bitdive.parent.trasirovka.agent.utils.objectMaperConfig;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import io.bitdive.parent.trasirovka.agent.utils.ReflectionUtils;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.time.temporal.Temporal;
import java.util.Date;
import java.util.UUID;

/**
 * Универсальный сериализатор ключей Map.
 * Для стандартных типов Java кодирует тип в строке ключа (формат "тип:fullyQualifiedClassName:значение").
 * Остальные типы (в т.ч. Class, свои POJO) обрабатываются через {@link ReflectionUtils#objectToStringForKey(Object)}.
 *
 * Явно обрабатываемые стандартные типы Java:
 * <ul>
 *   <li>null</li>
 *   <li>java.lang: String, Number (Integer, Long, Double, Float, Byte, Short), Boolean, Character, Enum</li>
 *   <li>java.math: BigInteger, BigDecimal (через Number)</li>
 *   <li>java.util: Date, UUID</li>
 *   <li>java.time: все Temporal (LocalDate, LocalDateTime, Instant, ZonedDateTime, …)</li>
 *   <li>java.net: URI, URL</li>
 * </ul>
 * Прочие типы (Class, произвольные объекты) → {@link ReflectionUtils#objectToStringForKey(Object)}.
 */
public class DefaultObjectKeySerializer extends JsonSerializer<Object> {

    /** Стандартные типы Java — префиксы для однозначного восстановления при десериализации. */
    private static final String PREFIX_ENUM = "enum:";
    private static final String PREFIX_STRING = "string:";
    private static final String PREFIX_NUMBER = "number:";
    private static final String PREFIX_BOOLEAN = "boolean:";
    private static final String PREFIX_CHARACTER = "character:";
    private static final String PREFIX_UUID = "uuid:";
    private static final String PREFIX_TEMPORAL = "temporal:";
    private static final String PREFIX_URI = "uri:";

    @Override
    public void serialize(Object value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        if (value == null) {
            gen.writeFieldName("null");
            return;
        }

        Class<?> clazz = value.getClass();

        if (value instanceof Enum) {
            Enum<?> e = (Enum<?>) value;
            gen.writeFieldName(PREFIX_ENUM + clazz.getName() + ":" + e.name());
            return;
        }

        if (value instanceof String) {
            gen.writeFieldName(PREFIX_STRING + clazz.getName() + ":" + (String) value);
            return;
        }

        if (value instanceof Number) {
            gen.writeFieldName(PREFIX_NUMBER + clazz.getName() + ":" + value);
            return;
        }

        if (value instanceof Boolean) {
            gen.writeFieldName(PREFIX_BOOLEAN + clazz.getName() + ":" + value);
            return;
        }

        if (value instanceof Character) {
            gen.writeFieldName(PREFIX_CHARACTER + clazz.getName() + ":" + value);
            return;
        }

        if (value instanceof UUID) {
            gen.writeFieldName(PREFIX_UUID + clazz.getName() + ":" + value);
            return;
        }

        if (value instanceof Temporal) {
            gen.writeFieldName(PREFIX_TEMPORAL + clazz.getName() + ":" + value);
            return;
        }

        if (value instanceof Date) {
            gen.writeFieldName(PREFIX_TEMPORAL + clazz.getName() + ":" + ((Date) value).getTime());
            return;
        }

        if (value instanceof URI || value instanceof URL) {
            gen.writeFieldName(PREFIX_URI + clazz.getName() + ":" + value);
            return;
        }

        // Остальные типы — через mapper (для сложных объектов может быть @class внутри JSON-строки)
        String keyAsJson = ReflectionUtils.objectToStringForKey(value);
        if (keyAsJson == null || keyAsJson.isEmpty()) {
            keyAsJson = String.valueOf(value);
        }
        gen.writeFieldName(keyAsJson);
    }
}

