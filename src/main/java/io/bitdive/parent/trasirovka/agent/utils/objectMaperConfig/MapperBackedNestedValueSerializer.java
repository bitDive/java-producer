package io.bitdive.parent.trasirovka.agent.utils.objectMaperConfig;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.util.Objects;
import java.util.function.Supplier;

public class MapperBackedNestedValueSerializer implements NestedValueSerializer {

    private final Supplier<ObjectMapper> mapperSupplier;

    public MapperBackedNestedValueSerializer(Supplier<ObjectMapper> mapperSupplier) {
        this.mapperSupplier = Objects.requireNonNull(mapperSupplier, "mapperSupplier");
    }

    @Override
    public String trySerialize(Object value) {
        if (value == null) {
            return "null";
        }

        // Throwable должен остаться под контролем ThrowableSerializerModule
        if (value instanceof Throwable) {
            return null;
        }

        // Совсем служебные объекты наружу не гоним
        if (value instanceof ObjectMapper
                || value instanceof JsonGenerator
                || value instanceof SerializerProvider) {
            return null;
        }

        ObjectMapper mapper = mapperSupplier.get();
        if (mapper == null) {
            return null;
        }

        try {
            return mapper.writeValueAsString(value);
        } catch (Throwable e) {
            return null;
        }
    }
}