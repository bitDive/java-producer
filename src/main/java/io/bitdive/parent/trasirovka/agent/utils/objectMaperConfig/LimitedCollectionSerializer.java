package io.bitdive.parent.trasirovka.agent.utils.objectMaperConfig;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.type.WritableTypeId;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.jsontype.TypeSerializer;

import java.io.IOException;
import java.util.Collection;

@SuppressWarnings("unchecked")
public class LimitedCollectionSerializer extends JsonSerializer<Collection<?>> {

    private final JsonSerializer<Object> delegate;
    private final int maxElements;
    private final String messageMaxElements;

    public LimitedCollectionSerializer(JsonSerializer<?> delegate, int maxElements, String messageMaxElements) {
        this.delegate = (JsonSerializer<Object>) delegate;
        this.maxElements = maxElements;
        this.messageMaxElements = messageMaxElements;
    }

    @Override
    public void serialize(Collection<?> value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        if (value == null) {
            serializers.defaultSerializeNull(gen);
            return;
        }

        // Если лимит не превышен — лучше отдать стандартному сериализатору
        if (value.size() <= maxElements) {
            delegate.serialize(value, gen, serializers);
            return;
        }

        // Иначе пишем ограниченную коллекцию вручную
        gen.writeStartArray();
        writeLimitedContents(value, gen, serializers);
        gen.writeEndArray();
    }

    @Override
    public void serializeWithType(Collection<?> value,
                                  JsonGenerator gen,
                                  SerializerProvider serializers,
                                  TypeSerializer typeSer) throws IOException {
        if (value == null) {
            serializers.defaultSerializeNull(gen);
            return;
        }

        if (value.size() <= maxElements) {
            delegate.serializeWithType(value, gen, serializers, typeSer);
            return;
        }

        WritableTypeId typeId = typeSer.writeTypePrefix(gen, typeSer.typeId(value, JsonToken.START_ARRAY));
        writeLimitedContents(value, gen, serializers);
        typeSer.writeTypeSuffix(gen, typeId);
    }

    private void writeLimitedContents(Collection<?> value,
                                      JsonGenerator gen,
                                      SerializerProvider serializers) throws IOException {
        int count = 0;
        for (Object item : value) {
            if (count >= maxElements) {
                break;
            }

            if (item == null) {
                serializers.defaultSerializeNull(gen);
            } else {
                serializers.defaultSerializeValue(item, gen);
            }
            count++;
        }
    }
}