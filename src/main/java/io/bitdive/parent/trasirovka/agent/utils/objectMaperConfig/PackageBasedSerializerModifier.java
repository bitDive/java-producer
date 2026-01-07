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
import java.util.Optional;

/**
 * Serializer modifier that replaces serialization of objects from excluded packages (and Servlet Request/Response wrappers)
 * with a safe placeholder object that preserves @class information for replay/testing.
 */
public class PackageBasedSerializerModifier extends BeanSerializerModifier {

    private final String[] excludedPackages;

    public PackageBasedSerializerModifier(String... excludedPackages) {
        this.excludedPackages = excludedPackages;
    }

    @Override
    public JsonSerializer<?> modifySerializer(SerializationConfig config,
                                              BeanDescription beanDesc,
                                              JsonSerializer<?> serializer) {
        Class<?> beanClass = beanDesc.getBeanClass();
        if (beanClass == null) {
            return serializer;
        }

        // ✅ 1) Any ServletRequest/ServletResponse (including Spring Security wrappers) must be excluded
        if (isServletRelatedClass(beanClass)) {
            return new ExcludedPackageSerializer();
        }

        // ✅ 2) Exclude by package prefix list
        String packageName = Optional.ofNullable(beanClass.getPackage())
                .map(Package::getName)
                .orElse(null);

        if (packageName == null) {
            return serializer;
        }

        for (String excludedPackage : excludedPackages) {
            if (excludedPackage != null && !excludedPackage.isEmpty() && packageName.startsWith(excludedPackage)) {
                return new ExcludedPackageSerializer();
            }
        }

        return serializer;
    }

    /**
     * Detects servlet-related objects via implemented interfaces (by name) to avoid hard dependency on servlet-api.
     * This catches wrappers like StrictFirewalledRequest, HeaderWriterRequest, etc.
     */
    private static boolean isServletRelatedClass(Class<?> cls) {
        return implementsInterfaceByName(cls, "javax.servlet.ServletRequest")
                || implementsInterfaceByName(cls, "jakarta.servlet.ServletRequest")
                || implementsInterfaceByName(cls, "javax.servlet.ServletResponse")
                || implementsInterfaceByName(cls, "jakarta.servlet.ServletResponse")
                || implementsInterfaceByName(cls, "javax.servlet.http.HttpServletRequest")
                || implementsInterfaceByName(cls, "jakarta.servlet.http.HttpServletRequest")
                || implementsInterfaceByName(cls, "javax.servlet.http.HttpServletResponse")
                || implementsInterfaceByName(cls, "jakarta.servlet.http.HttpServletResponse");
    }

    private static boolean implementsInterfaceByName(Class<?> cls, String ifaceName) {
        if (cls == null || cls == Object.class) {
            return false;
        }

        for (Class<?> i : cls.getInterfaces()) {
            if (ifaceName.equals(i.getName())) {
                return true;
            }
            // interfaces can extend interfaces
            if (implementsInterfaceByName(i, ifaceName)) {
                return true;
            }
        }

        // class hierarchy
        return implementsInterfaceByName(cls.getSuperclass(), ifaceName);
    }

    /**
     * Serializer for excluded objects.
     * Writes a safe placeholder object and preserves runtime class name in "@class".
     */
    private static class ExcludedPackageSerializer extends StdSerializer<Object> {

        protected ExcludedPackageSerializer() {
            super(Object.class);
        }

        @Override
        public void serialize(Object value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            if (value == null) {
                gen.writeNull();
                return;
            }

            // Avoid calling any methods on the object; only reflect its class.
            Class<?> clazz = value.getClass();
            String className = clazz.getName();
            String packageName = getPackageNameSafely(clazz);

            gen.writeStartObject();
            gen.writeStringField("@class", className);
            gen.writeStringField("value", "[excluded: " + packageName + "]");
            gen.writeEndObject();
        }

        @Override
        public void serializeWithType(Object value, JsonGenerator gen, SerializerProvider serializers,
                                      TypeSerializer typeSer) throws IOException {
            // We already write "@class" manually and must avoid TypeSerializer edge-cases in arrays/collections.
            serialize(value, gen, serializers);
        }

        private static String getPackageNameSafely(Class<?> clazz) {
            try {
                Package pkg = clazz.getPackage();
                if (pkg != null) {
                    return pkg.getName();
                }
            } catch (Exception ignored) {
                // ignore
            }

            String className = clazz.getName();
            int lastDot = className.lastIndexOf('.');
            if (lastDot > 0) {
                return className.substring(0, lastDot);
            }
            return "(default package)";
        }
    }
}
