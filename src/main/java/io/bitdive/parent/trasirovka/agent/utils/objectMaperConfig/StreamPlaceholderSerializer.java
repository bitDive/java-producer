package io.bitdive.parent.trasirovka.agent.utils.objectMaperConfig;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

import java.io.IOException;
import java.util.stream.Stream;

public final class StreamPlaceholderSerializer extends StdSerializer<Stream> {

    public StreamPlaceholderSerializer() {
        super(Stream.class);          // handledType = Stream.class
    }

    @Override
    public void serialize(Stream value,
                          JsonGenerator gen,
                          SerializerProvider provider) throws IOException {
        gen.writeString("[stream]");
    }
}
