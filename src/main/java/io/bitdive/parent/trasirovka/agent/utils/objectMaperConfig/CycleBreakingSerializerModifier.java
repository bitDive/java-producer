package io.bitdive.parent.trasirovka.agent.utils.objectMaperConfig;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.jsontype.TypeSerializer;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;

import java.io.IOException;
import java.util.IdentityHashMap;


public class CycleBreakingSerializerModifier extends BeanSerializerModifier {

    @Override
    public JsonSerializer<?> modifySerializer(SerializationConfig config,
                                              BeanDescription beanDesc,
                                              JsonSerializer<?> serializer) {
        Class<?> beanClass = beanDesc.getBeanClass();
        if (beanClass == null) {
            return serializer;
        }

        if (isSimpleType(beanClass)) {
            return serializer;
        }

        if (serializer instanceof CycleBreakingBeanSerializer) {
            return serializer;
        }

        return new CycleBreakingBeanSerializer(serializer);
    }

    private static boolean isSimpleType(Class<?> type) {
        if (type.isPrimitive() || type.isEnum()) {
            return true;
        }
        if (Number.class.isAssignableFrom(type)
                || CharSequence.class.isAssignableFrom(type)
                || Boolean.class == type
                || Character.class == type) {
            return true;
        }

        Package pkg = type.getPackage();
        if (pkg != null) {
            String name = pkg.getName();
            if (name.startsWith("java.") || name.startsWith("javax.")) {
                return true;
            }
        }

        return false;
    }

    static final class CycleBreakingBeanSerializer extends JsonSerializer<Object> {

        private static final Object VISITED_KEY = new Object();

        @SuppressWarnings("unchecked")
        private static IdentityHashMap<Object, Boolean> getVisited(SerializerProvider serializers) {
            IdentityHashMap<Object, Boolean> visited =
                    (IdentityHashMap<Object, Boolean>) serializers.getAttribute(VISITED_KEY);
            if (visited == null) {
                visited = new IdentityHashMap<>();
                serializers.setAttribute(VISITED_KEY, visited);
            }
            return visited;
        }

        private final JsonSerializer<Object> delegate;

        @SuppressWarnings("unchecked")
        CycleBreakingBeanSerializer(JsonSerializer<?> delegate) {
            this.delegate = (JsonSerializer<Object>) delegate;
        }

        @Override
        public void serialize(Object value,
                              JsonGenerator gen,
                              SerializerProvider serializers) throws IOException {
            if (value == null) {
                serializers.defaultSerializeNull(gen);
                return;
            }

            IdentityHashMap<Object, Boolean> visited = getVisited(serializers);
            if (visited.containsKey(value)) {
                serializers.defaultSerializeNull(gen);
                return;
            }

            visited.put(value, Boolean.TRUE);
            try {
                delegate.serialize(value, gen, serializers);
            } finally {
                visited.remove(value);
            }
        }

        @Override
        public void serializeWithType(Object value,
                                      JsonGenerator gen,
                                      SerializerProvider serializers,
                                      TypeSerializer typeSer) throws IOException {
            if (value == null) {
                serializers.defaultSerializeNull(gen);
                return;
            }

            IdentityHashMap<Object, Boolean> visited = getVisited(serializers);
            if (visited.containsKey(value)) {
                serializers.defaultSerializeNull(gen);
                return;
            }

            visited.put(value, Boolean.TRUE);
            try {
                delegate.serializeWithType(value, gen, serializers, typeSer);
            } finally {
                visited.remove(value);
            }
        }
    }
}

