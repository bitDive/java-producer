package io.bitdive.parent.trasirovka.agent.utils.objectMaperConfig;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.jsontype.TypeSerializer;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;

import java.io.IOException;

public final class LambdaSerializerModifier extends BeanSerializerModifier {

    private final JsonSerializer<Object> lambdaSerializer = new LambdaAsFunctionMethodSerializer();

    @Override
    public JsonSerializer<?> modifySerializer(SerializationConfig config,
                                              BeanDescription beanDesc,
                                              JsonSerializer<?> serializer) {
        Class<?> raw = beanDesc.getBeanClass();
        if (isLambdaClass(raw)) {
            return lambdaSerializer;
        }
        return serializer;
    }

    private static boolean isLambdaClass(Class<?> c) {
        return c.isSynthetic() && c.getName().contains("$$Lambda$");
    }

    public static final class LambdaAsFunctionMethodSerializer extends JsonSerializer<Object> {

        @Override
        public void serialize(Object value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            gen.writeStartObject();
            gen.writeStringField("@Type", "FunctionMethod");
            gen.writeStringField("lambdaClass", value.getClass().getName());
            gen.writeEndObject();
            gen.writeEndObject();
        }

        @Override
        public void serializeWithType(Object value,
                                      JsonGenerator gen,
                                      SerializerProvider serializers,
                                      TypeSerializer typeSer) throws IOException {
            serialize(value, gen, serializers);
        }


    }
}
