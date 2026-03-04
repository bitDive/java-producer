package io.bitdive.parent.trasirovka.agent.utils.objectMaperConfig;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.jsontype.TypeSerializer;
import com.fasterxml.jackson.databind.module.SimpleModule;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

public class SpringOptionalSerializers {

    private SpringOptionalSerializers() { }

    public static void tryRegisterSpringSortSerializer(ObjectMapper mapper) {
        try {
            final Class<?> sortClass = Class.forName("org.springframework.data.domain.Sort");

            SimpleModule m = new SimpleModule("spring-sort-fix");
            m.addSerializer((Class) sortClass, new SortLikeSerializer());

            mapper.registerModule(m);

        } catch (ClassNotFoundException ignored) {
        }
    }

    private static final class SortLikeSerializer extends JsonSerializer<Object> {

        @Override
        public void serialize(Object value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            gen.writeStartObject();
            writeSortBody(value, gen);
            gen.writeEndObject();
        }

        @Override
        public void serializeWithType(Object value, JsonGenerator gen, SerializerProvider serializers, TypeSerializer typeSer)
                throws IOException {
            // TypeSerializer сам пишет старт/конец объекта для As.PROPERTY
            typeSer.writeTypePrefixForObject(value, gen);
            writeSortBody(value, gen);
            typeSer.writeTypeSuffixForObject(value, gen);
        }

        private void writeSortBody(Object sort, JsonGenerator gen) throws IOException {
            // orders = sort.toList()
            List orders = safeList(invoke(sort, "toList"));

            gen.writeArrayFieldStart("orders");
            for (int i = 0; i < orders.size(); i++) {
                Object o = orders.get(i);
                gen.writeStartObject();
                gen.writeStringField("property", String.valueOf(invoke(o, "getProperty")));

                Object dir = invoke(o, "getDirection");
                gen.writeStringField("direction", dir == null ? "ASC" : String.valueOf(invoke(dir, "name")));

                Object ignore = invoke(o, "isIgnoreCase");
                gen.writeBooleanField("ignoreCase", Boolean.TRUE.equals(ignore));

                Object nh = invoke(o, "getNullHandling");
                gen.writeStringField("nullHandling", nh == null ? "NATIVE" : String.valueOf(invoke(nh, "name")));

                gen.writeEndObject();
            }
            gen.writeEndArray();

            // опциональные флаги (не обязательны, но удобно)
            gen.writeBooleanField("empty", Boolean.TRUE.equals(invoke(sort, "isEmpty")));
            gen.writeBooleanField("sorted", Boolean.TRUE.equals(invoke(sort, "isSorted")));
            gen.writeBooleanField("unsorted", Boolean.TRUE.equals(invoke(sort, "isUnsorted")));
        }

        private Object invoke(Object target, String method) {
            try {
                Method m = target.getClass().getMethod(method);
                m.setAccessible(true);
                return m.invoke(target);
            } catch (Exception e) {
                return null;
            }
        }

        private List safeList(Object maybeList) {
            if (maybeList instanceof List) {
                return (List) maybeList;
            }
            return Collections.emptyList();
        }
    }
}
