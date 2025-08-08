package io.bitdive.parent.trasirovka.agent.utils.objectMaperConfig;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.sql.Blob;
import java.sql.Clob;
import java.util.stream.*;

public class FlowDataToPlaceholderModule extends SimpleModule {
    public FlowDataToPlaceholderModule() {


        addSerializer(byte[].class, new PlaceholderSerializer<>("[byte arrays]"));

        addSerializer(InputStream.class, new PlaceholderSerializer<>("[inputStream]"));
        addSerializer(Reader.class, new PlaceholderSerializer<>("[reader]"));
        addSerializer(ByteBuffer.class, new PlaceholderSerializer<>("[byteBuffer]"));

        addSerializer(IntStream.class, new PlaceholderSerializer<>("[int-stream]"));
        addSerializer(LongStream.class, new PlaceholderSerializer<>("[long-stream]"));
        addSerializer(DoubleStream.class, new PlaceholderSerializer<>("[double-stream]"));
        addSerializer(BaseStream.class, new PlaceholderSerializer<>("[stream]"));
        addSerializer(Stream.class, new PlaceholderSerializer<>("[stream]"));

        addSerializer(Blob.class, new PlaceholderSerializer<>("[blob]"));
        addSerializer(Clob.class, new PlaceholderSerializer<>("[clob]"));
    }

    private static final class PlaceholderSerializer<T> extends JsonSerializer<T> {
        private final String marker;

        PlaceholderSerializer(String marker) {
            this.marker = marker;
        }

        @Override
        public void serialize(T value, JsonGenerator gen, SerializerProvider prov) throws IOException {
            gen.writeString(marker);
        }
    }
}
