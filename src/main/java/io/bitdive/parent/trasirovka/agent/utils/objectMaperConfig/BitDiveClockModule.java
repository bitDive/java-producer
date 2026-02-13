package io.bitdive.parent.trasirovka.agent.utils.objectMaperConfig;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.jsontype.TypeSerializer;
import com.fasterxml.jackson.databind.module.SimpleModule;

public final class BitDiveClockModule extends SimpleModule {

    public BitDiveClockModule() {
        super("BitDiveClockModule");

        // Сериализация для всех Clock (в т.ч. Clock$SystemClock)
        addSerializer(Clock.class, new ClockSnapshotSerializer());

        // Если где-то десериализуешь Clock напрямую
        addDeserializer(Clock.class, new ClockFixedDeserializer());

        // Если у тебя включён default typing и typeId = "java.time.Clock$SystemClock" и т.п.,
        // то Джексон будет пытаться создать ИМЕННО этот класс -> регистрируем десериализаторы под конкретные внутренние типы.
        registerInternalClock("java.time.Clock$SystemClock", new SystemClockDeserializer());
        registerInternalClock("java.time.Clock$FixedClock", new FixedClockDeserializer());
        registerInternalClock("java.time.Clock$OffsetClock", new OffsetClockDeserializer());
        registerInternalClock("java.time.Clock$TickClock", new TickClockDeserializer());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void registerInternalClock(String fqcn, JsonDeserializer<? extends Clock> deser) {
        try {
            Class<?> raw = Class.forName(fqcn);
            if (Clock.class.isAssignableFrom(raw)) {
                addDeserializer((Class) raw, (JsonDeserializer) deser);
            }
        } catch (ClassNotFoundException ignored) {
            // зависит от JDK; если нет класса — просто пропускаем
        }
    }

    // --- Serializer ---

    static final class ClockSnapshotSerializer extends JsonSerializer<Clock> {

        @Override
        public void serialize(Clock value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            gen.writeStartObject();
            writeFields(value, gen);
            gen.writeEndObject();
        }

        // КЛЮЧЕВОЕ: поддержка type id
        @Override
        public void serializeWithType(Clock value,
                                      JsonGenerator gen,
                                      SerializerProvider serializers,
                                      TypeSerializer typeSer) throws IOException {
            typeSer.writeTypePrefixForObject(value, gen);
            writeFields(value, gen);
            typeSer.writeTypeSuffixForObject(value, gen);
        }

        private static void writeFields(Clock value, JsonGenerator gen) throws IOException {
            gen.writeStringField("impl", value.getClass().getName());
            gen.writeStringField("zone", safeZoneId(value));
            gen.writeStringField("instant", safeInstant(value)); // снимок на момент сериализации
            gen.writeStringField("toString", String.valueOf(value));
        }

        private static String safeZoneId(Clock c) {
            try {
                ZoneId z = c.getZone();
                return z == null ? null : z.getId();
            } catch (Throwable t) {
                return null;
            }
        }

        private static String safeInstant(Clock c) {
            try {
                Instant i = c.instant();
                return i == null ? null : i.toString();
            } catch (Throwable t) {
                return null;
            }
        }
    }

    // --- Deserializers (опционально, но полезно если у тебя есть default typing) ---

    static final class ClockFixedDeserializer extends StdDeserializer<Clock> {
        ClockFixedDeserializer() { super(Clock.class); }

        @Override
        public Clock deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            JsonNode n = p.getCodec().readTree(p);
            ZoneId zone = parseZone(n);
            Instant instant = parseInstant(n);
            return (instant != null) ? Clock.fixed(instant, zone) : Clock.system(zone);
        }
    }

    static final class SystemClockDeserializer extends StdDeserializer<Clock> {
        SystemClockDeserializer() { super(Clock.class); }

        @Override
        public Clock deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            JsonNode n = p.getCodec().readTree(p);
            return Clock.system(parseZone(n)); // вернёт Clock$SystemClock
        }
    }

    static final class FixedClockDeserializer extends StdDeserializer<Clock> {
        FixedClockDeserializer() { super(Clock.class); }

        @Override
        public Clock deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            JsonNode n = p.getCodec().readTree(p);
            ZoneId zone = parseZone(n);
            Instant instant = parseInstant(n);
            if (instant == null) instant = Instant.EPOCH;
            return Clock.fixed(instant, zone); // вернёт Clock$FixedClock
        }
    }

    static final class OffsetClockDeserializer extends StdDeserializer<Clock> {
        OffsetClockDeserializer() { super(Clock.class); }

        @Override
        public Clock deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            JsonNode n = p.getCodec().readTree(p);
            // offset/baseClock у JDK Clock спрятаны — делаем безопасный вариант
            return Clock.offset(Clock.system(parseZone(n)), Duration.ZERO); // вернёт Clock$OffsetClock
        }
    }

    static final class TickClockDeserializer extends StdDeserializer<Clock> {
        TickClockDeserializer() { super(Clock.class); }

        @Override
        public Clock deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            JsonNode n = p.getCodec().readTree(p);
            return Clock.tick(Clock.system(parseZone(n)), Duration.ofMillis(1)); // вернёт Clock$TickClock
        }
    }

    private static ZoneId parseZone(JsonNode n) {
        String z = n.path("zone").asText(null);
        try {
            if (z == null) {
                return ZoneId.systemDefault();
            }
            z = z.trim();
            return z.isEmpty() ? ZoneId.systemDefault() : ZoneId.of(z);
        } catch (Throwable t) {
            return ZoneId.systemDefault();
        }
    }

    private static Instant parseInstant(JsonNode n) {
        String s = n.path("instant").asText(null);
        try {
            if (s == null) {
                return null;
            }
            s = s.trim();
            return s.isEmpty() ? null : Instant.parse(s);
        } catch (Throwable t) {
            return null;
        }
    }
}
