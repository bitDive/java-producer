package io.bitdive.parent.trasirovka.agent.utils.objectMaperConfig;

import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;

import java.util.Collection;

public class CollectionSizeLimiter extends BeanSerializerModifier {
    private final int maxElements;
    private final String messageMaxElements;

    public CollectionSizeLimiter(int maxElements, String messageMaxElements) {
        this.maxElements = maxElements;
        this.messageMaxElements = messageMaxElements;
    }

    @Override
    public JsonSerializer<?> modifySerializer(SerializationConfig config,
                                              BeanDescription beanDesc,
                                              JsonSerializer<?> serializer) {
        if (Collection.class.isAssignableFrom(beanDesc.getBeanClass())) {
            return new LimitedCollectionSerializer(maxElements, messageMaxElements);
        }
        return serializer;
    }
}
