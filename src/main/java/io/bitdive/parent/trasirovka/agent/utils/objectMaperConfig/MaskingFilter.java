package io.bitdive.parent.trasirovka.agent.utils.objectMaperConfig;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.jsontype.TypeSerializer;
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
    @SuppressWarnings("unchecked")
    public JsonSerializer<?> modifySerializer(SerializationConfig config,
                                              BeanDescription beanDesc,
                                              JsonSerializer<?> serializer) {
        // Оборачиваем оригинальный сериализатор своим
        return new MaskingSerializer((JsonSerializer<Object>) serializer, maskedFields);
    }

    static class MaskingSerializer extends StdSerializer<Object> {
        private final JsonSerializer<Object> defaultSerializer;
        private final Set<String> maskedFields;

        protected MaskingSerializer(JsonSerializer<Object> defaultSerializer,
                                    Set<String> maskedFields) {
            super(Object.class);
            this.defaultSerializer = defaultSerializer;
            this.maskedFields = maskedFields;
        }

        private boolean shouldMask(JsonGenerator gen) {
            String fieldName = gen.getOutputContext().getCurrentName();
            return fieldName != null && maskedFields.contains(fieldName);
        }

        @Override
        public void serialize(Object value,
                              JsonGenerator gen,
                              SerializerProvider provider) throws IOException {
            if (shouldMask(gen)) {
                // Маскируем значение поля
                gen.writeString("****");
            } else {
                // Обычная сериализация делегату
                defaultSerializer.serialize(value, gen, provider);
            }
        }

        @Override
        public void serializeWithType(Object value,
                                      JsonGenerator gen,
                                      SerializerProvider provider,
                                      TypeSerializer typeSer) throws IOException {
            if (shouldMask(gen)) {
                // Для замаскированных полей просто пишем "****"
                // (type-id не пишем, т.к. всё равно скрываем реальный тип и значение)
                gen.writeString("****");
            } else if (defaultSerializer != null) {
                // Делегируем в оригинальный сериализатор, он уже умеет работать с typeSer
                defaultSerializer.serializeWithType(value, gen, provider, typeSer);
            } else {
                // Fallback, на всякий случай
                serialize(value, gen, provider);
            }
        }
    }
}
