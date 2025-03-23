package io.bitdive.parent.trasirovka.agent.utils.objectMaperConfig;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

import java.io.IOException;
import java.util.Set;

public class MaskingFilter extends BeanSerializerModifier {
    private final Set<String> maskedFields;

    public MaskingFilter(Set<String> maskedFields) {
        this.maskedFields = maskedFields;
    }

    @Override
    public JsonSerializer<?> modifySerializer(SerializationConfig config, BeanDescription beanDesc, JsonSerializer<?> serializer) {
        return new MaskingSerializer((JsonSerializer<Object>) serializer, maskedFields);
    }

    static class MaskingSerializer extends StdSerializer<Object> {
        private final JsonSerializer<Object> defaultSerializer;
        private final Set<String> maskedFields;

        protected MaskingSerializer(JsonSerializer<Object> defaultSerializer, Set<String> maskedFields) {
            super(Object.class);
            this.defaultSerializer = defaultSerializer;
            this.maskedFields = maskedFields;
        }

        @Override
        public void serialize(Object value, JsonGenerator gen, SerializerProvider provider) throws IOException {
            if (maskedFields.contains(gen.getOutputContext().getCurrentName())) {
                gen.writeString("****"); // Заменяем значение на "****"
            } else {
                defaultSerializer.serialize(value, gen, provider);
            }
        }
    }
}
