package io.bitdive.parent.trasirovka.agent.utils.objectMaperConfig;

import com.fasterxml.jackson.databind.introspect.Annotated;
import com.fasterxml.jackson.databind.introspect.JacksonAnnotationIntrospector;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class IgnoreForeignSerDeIntrospector extends JacksonAnnotationIntrospector {

    private final String allowedPrefix;
    private final ConcurrentMap<Class<?>, Boolean> allowedCache = new ConcurrentHashMap<Class<?>, Boolean>();

    public IgnoreForeignSerDeIntrospector(String allowedPrefix) {
        this.allowedPrefix = allowedPrefix;
    }

    private boolean isAllowed(final Class<?> c) {
        Boolean cached = allowedCache.get(c);
        if (cached != null) {
            return cached.booleanValue();
        }
        boolean allowed = c.getName().startsWith(allowedPrefix);
        allowedCache.putIfAbsent(c, Boolean.valueOf(allowed));
        return allowed;
    }

    @Override
    public Object findSerializer(Annotated a) {
        Object ser = super.findSerializer(a);
        if (ser instanceof Class) {
            Class<?> c = (Class<?>) ser;
            if (!isAllowed(c)) {
                return null; // игнорируем чужой @JsonSerialize(using=...)
            }
        }
        return ser;
    }

    @Override
    public Object findDeserializer(Annotated a) {
        Object deser = super.findDeserializer(a);
        if (deser instanceof Class) {
            Class<?> c = (Class<?>) deser;
            if (!isAllowed(c)) {
                return null; // игнорируем чужой @JsonDeserialize(using=...)
            }
        }
        return deser;
    }
}

