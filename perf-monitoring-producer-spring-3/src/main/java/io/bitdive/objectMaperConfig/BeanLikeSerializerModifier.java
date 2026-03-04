package io.bitdive.objectMaperConfig;

import io.bitdive.shaded.com.fasterxml.jackson.core.JsonGenerator;
import io.bitdive.shaded.com.fasterxml.jackson.databind.BeanDescription;
import io.bitdive.shaded.com.fasterxml.jackson.databind.JsonSerializer;
import io.bitdive.shaded.com.fasterxml.jackson.databind.SerializationConfig;
import io.bitdive.shaded.com.fasterxml.jackson.databind.SerializerProvider;
import io.bitdive.shaded.com.fasterxml.jackson.databind.jsontype.TypeSerializer;
import io.bitdive.shaded.com.fasterxml.jackson.databind.ser.BeanSerializerModifier;
import io.bitdive.utilsBean.SpringBeanTypeIndex;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class BeanLikeSerializerModifier extends BeanSerializerModifier {

    private final JsonSerializer<Object> placeholder;

    public BeanLikeSerializerModifier() {
        this.placeholder = new BeanLikePlaceholderSerializer();
    }

    @Override
    public JsonSerializer<?> modifySerializer(SerializationConfig config,
                                              BeanDescription beanDesc,
                                              JsonSerializer<?> serializer) {
        Class<?> raw = beanDesc.getBeanClass();

        if (BeanLikeDetector.isBeanLikeClass(raw)) {
            return placeholder;
        }
        return serializer;
    }

    public static final class BeanLikePlaceholderSerializer extends JsonSerializer<Object> {

        @Override
        public void serialize(Object v, JsonGenerator g, SerializerProvider sp) throws IOException {
            Class<?> runtimeClass = v.getClass();
            Class<?> userClass = BeanLikeDetector.getUserClass(runtimeClass);

            g.writeStartObject();
            g.writeStringField("@Type", "springBean");
            g.writeStringField("proxyType", BeanLikeDetector.detectProxyType(runtimeClass));
            g.writeStringField("runtimeClass", runtimeClass.getName());
            g.writeStringField("userClass", userClass.getName());
            g.writeEndObject();
        }

        @Override
        public void serializeWithType(Object v,
                                      JsonGenerator g,
                                      SerializerProvider sp,
                                      TypeSerializer typeSer) throws IOException {
            Class<?> runtimeClass = v.getClass();
            Class<?> userClass = BeanLikeDetector.getUserClass(runtimeClass);

            // typeSer сам пишет старт объекта + type id (например @class)
            typeSer.writeTypePrefixForObject(v, g);

            g.writeStringField("@Type", "springBean");
            g.writeStringField("proxyType", BeanLikeDetector.detectProxyType(runtimeClass));
            g.writeStringField("runtimeClass", runtimeClass.getName());
            g.writeStringField("userClass", userClass.getName());

            typeSer.writeTypeSuffixForObject(v, g);
        }
    }

    public static final class BeanLikeDetector {

        private BeanLikeDetector() {}

        private static final Map<Class<?>, Boolean> CACHE = new ConcurrentHashMap<>();

        private static final String[] PROXY_MARKERS = new String[] {
                "$$SpringCGLIB$$",
                "$$EnhancerBySpringCGLIB$$",
                "CGLIB",
                "ByteBuddy"
        };

        public static boolean isBeanLikeClass(Class<?> cls) {
            if (cls == null) return false;
            return CACHE.computeIfAbsent(cls, BeanLikeDetector::calc);
        }

        private static boolean calc(Class<?> cls) {
            // 1) JDK proxy
            if (Proxy.isProxyClass(cls)) return true;

            // 2) CGLIB/ByteBuddy proxy markers
            String n = cls.getName();
            for (String m : PROXY_MARKERS) {
                if (n.contains(m)) return true;
            }

            // 3) Главное: "по типу" — если для userClass есть bean в контейнере
            Class<?> user = getUserClass(cls);
            return SpringBeanTypeIndex.isBeanType(user);
        }

        public static Class<?> getUserClass(Class<?> cls) {
            if (cls == null) return Object.class;

            String n = cls.getName();
            if (n.contains("$$SpringCGLIB$$") || n.contains("$$EnhancerBySpringCGLIB$$") || n.contains("CGLIB")) {
                Class<?> sc = cls.getSuperclass();
                if (sc != null && sc != Object.class) return sc;
            }
            return cls;
        }

        public static String detectProxyType(Class<?> cls) {
            if (cls == null) return "NONE";
            if (Proxy.isProxyClass(cls)) return "JDK_PROXY";

            String n = cls.getName();
            if (n.contains("$$SpringCGLIB$$") || n.contains("$$EnhancerBySpringCGLIB$$") || n.contains("CGLIB")) return "CGLIB";
            if (n.contains("ByteBuddy")) return "BYTE_BUDDY";
            return "NONE";
        }
    }
}
