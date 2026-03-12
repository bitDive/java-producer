package io.bitdive.parent.trasirovka.agent.utils.objectMaperConfig;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.introspect.Annotated;
import com.fasterxml.jackson.databind.introspect.AnnotatedMember;
import com.fasterxml.jackson.databind.introspect.NopAnnotationIntrospector;

public class IgnoreAwareDeserializationIntrospector extends NopAnnotationIntrospector {
    @Override
    public boolean hasIgnoreMarker(AnnotatedMember m) {
        // Если рядом с JsonIgnore есть хотя бы один из
        // JsonDeserialize(using=...) или JsonSerialize(using=...),
        // то поле НЕ игнорируем (даём работать кастомным (де)сериализаторам).
        if (hasJsonDeserialize(m) || hasJsonSerialize(m)) {
            return false;
        }
        JsonIgnore ann = m.getAnnotation(JsonIgnore.class);
        return ann != null && ann.value();
    }

    @Override
    public Object findDeserializer(Annotated a) {
        JsonDeserialize ann = a.getAnnotation(JsonDeserialize.class);
        if (ann == null) {
            return null;
        }

        Class<? extends JsonDeserializer> using = ann.using();
        if (using == null || using == JsonDeserializer.None.class) {
            return null;
        }

        return using;
    }

    private boolean hasJsonDeserialize(Annotated a) {
        JsonDeserialize ann = a.getAnnotation(JsonDeserialize.class);
        if (ann == null) {
            return false;
        }
        return ann.using() != JsonDeserializer.None.class;
    }

    private boolean hasJsonSerialize(Annotated a) {
        JsonSerialize ann = a.getAnnotation(JsonSerialize.class);
        if (ann == null) {
            return false;
        }
        Class<? extends JsonSerializer> using = ann.using();
        return using != null && using != JsonSerializer.None.class;
    }
}
