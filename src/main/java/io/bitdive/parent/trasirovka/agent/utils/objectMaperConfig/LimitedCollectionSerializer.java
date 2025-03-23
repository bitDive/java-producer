package io.bitdive.parent.trasirovka.agent.utils.objectMaperConfig;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;
import java.util.Collection;

public class LimitedCollectionSerializer extends JsonSerializer<Collection<?>> {
    private final int maxElements;
    private final String messageMaxElements;

    public LimitedCollectionSerializer(int maxElements, String messageMaxElements) {
        this.maxElements = maxElements;
        this.messageMaxElements = messageMaxElements;
    }

    @Override
    public void serialize(Collection<?> value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        gen.writeStartArray();
        int count = 0;
        for (Object elem : value) {
            if (count < maxElements) {
                // Сериализуем элемент стандартным способом
                serializers.defaultSerializeValue(elem, gen);
            } else if (count == maxElements) {
                // Вместо следующих элементов записываем сообщение и выходим
                gen.writeString(this.messageMaxElements);
                break;
            }
            count++;
        }
        gen.writeEndArray();
    }
}