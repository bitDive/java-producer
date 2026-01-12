package io.bitdive.parent.trasirovka.agent.utils.objectMaperConfig;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.PropertyName;
import com.fasterxml.jackson.databind.introspect.Annotated;
import com.fasterxml.jackson.databind.introspect.AnnotatedMember;
import com.fasterxml.jackson.databind.introspect.JacksonAnnotationIntrospector;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;

public final class OnlyJsonIgnoreIntrospector extends JacksonAnnotationIntrospector {

    @Override
    public boolean hasIgnoreMarker(AnnotatedMember m) {
        try {
            JsonIgnore ann = _findAnnotation(m, JsonIgnore.class);
            if (ann != null) {
                return ann.value();
            }
        } catch (Throwable ignored) { }

        AnnotatedElement el = null;

        Object annotated = m.getAnnotated();
        if (annotated instanceof AnnotatedElement) {
            el = (AnnotatedElement) annotated;
        } else if (m.getMember() instanceof AnnotatedElement) {
            el = (AnnotatedElement) m.getMember();
        }

        if (el != null) {
            for (Annotation a : el.getDeclaredAnnotations()) {
                String n = a.annotationType().getName();
                if ("com.fasterxml.jackson.annotation.JsonIgnore".equals(n) || n.endsWith(".JsonIgnore")) {
                    try {
                        Method value = a.annotationType().getMethod("value");
                        Object v = value.invoke(a);
                        return !(v instanceof Boolean) || ((Boolean) v).booleanValue();
                    } catch (Exception e) {
                        return true; // если не смогли прочитать value — считаем ignored
                    }
                }
            }
        }

        return false;
    }

    @Override public PropertyName findNameForSerialization(Annotated a) { return null; }
    @Override public PropertyName findNameForDeserialization(Annotated a) { return null; }
    @Override public Object findSerializer(Annotated a) { return null; }
    @Override public Object findDeserializer(Annotated a) { return null; }
    @Override public com.fasterxml.jackson.annotation.JsonInclude.Value findPropertyInclusion(Annotated a) { return null; }
    @Override public com.fasterxml.jackson.annotation.JsonFormat.Value findFormat(Annotated a) { return null; }
    @Override public Class<?>[] findViews(Annotated a) { return null; }
}
