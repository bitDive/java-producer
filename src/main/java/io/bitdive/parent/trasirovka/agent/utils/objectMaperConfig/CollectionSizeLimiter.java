package io.bitdive.parent.trasirovka.agent.utils.objectMaperConfig;

import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;
import com.fasterxml.jackson.databind.type.CollectionType;

public class CollectionSizeLimiter extends BeanSerializerModifier {
    private final int maxElements;
    private final String messageMaxElements;

    public CollectionSizeLimiter(int maxElements, String messageMaxElements) {
        this.maxElements = maxElements;
        this.messageMaxElements = messageMaxElements;
    }

    @Override
    public JsonSerializer<?> modifyCollectionSerializer(
            SerializationConfig config,
            CollectionType valueType,
            BeanDescription beanDesc,
            JsonSerializer<?> serializer
    ) {
        return new LimitedCollectionSerializer(serializer, maxElements, messageMaxElements);
    }
}