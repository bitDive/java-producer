package io.bitdive.parent.trasirovka.agent.utils.objectMaperConfig;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.PropertyName;
import com.fasterxml.jackson.databind.introspect.Annotated;
import com.fasterxml.jackson.databind.introspect.AnnotatedMember;
import com.fasterxml.jackson.databind.introspect.JacksonAnnotationIntrospector;

public final class OnlyJsonIgnoreIntrospector extends JacksonAnnotationIntrospector {

    @Override
    public boolean hasIgnoreMarker(AnnotatedMember m) {
        JsonIgnore ann = _findAnnotation(m, JsonIgnore.class);
        return ann != null && ann.value(); // @JsonIgnore(false) -> не игнорировать
    }

    // Ниже — жёстко отключаем влияние остальных jackson-аннотаций:
    @Override
    public PropertyName findNameForSerialization(Annotated a) { return null; }

    @Override
    public PropertyName findNameForDeserialization(Annotated a) { return null; }

    @Override
    public Object findSerializer(Annotated a) { return null; }

    @Override
    public Object findDeserializer(Annotated a) { return null; }

    @Override
    public com.fasterxml.jackson.annotation.JsonInclude.Value findPropertyInclusion(Annotated a) { return null; }

    @Override
    public com.fasterxml.jackson.annotation.JsonFormat.Value findFormat(Annotated a) { return null; }

    @Override
    public Class<?>[] findViews(Annotated a) { return null; }
}
