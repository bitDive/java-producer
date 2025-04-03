package io.bitdive.parent.trasirovka.agent.utils.objectMaperConfig;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;

import java.io.IOException;

public class ByteArrayToPlaceholderModule extends SimpleModule {
    public ByteArrayToPlaceholderModule() {
        addSerializer(byte[].class, new JsonSerializer<byte[]>() {
            @Override
            public void serialize(byte[] value,
                                  JsonGenerator gen,
                                  SerializerProvider serializers) throws IOException {
                gen.writeString("[byte arrays]");
            }
        });
    }
}
