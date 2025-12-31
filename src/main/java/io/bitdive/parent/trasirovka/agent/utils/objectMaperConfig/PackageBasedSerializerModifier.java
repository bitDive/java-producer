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

        // Проверяем на servlet-интерфейсы и классы (безопасная проверка по имени)
        String className = beanClass.getName();
        if (isServletRelatedClass(className)) {
            return new ExcludedPackageSerializer(beanClass);
        }

        // Проверяем исключенные пакеты
        String packageName = Optional.ofNullable(beanClass.getPackage())
                .map(Package::getName)
                .orElse(null);

        if (packageName == null) {
            return serializer;
        }

        for (String excludedPackage : excludedPackages) {
            if (packageName.startsWith(excludedPackage)) {
                // Используем StdSerializer для правильной поддержки TypeSerializer
                return new ExcludedPackageSerializer(beanClass);
            }
        }

        return serializer;
    }

    /**
     * Проверяет, является ли класс связанным с servlet API (безопасная проверка по имени класса).
     * Это предотвращает попытки сериализации HttpServletRequest/Response и их оберток.
     */
    private static boolean isServletRelatedClass(String className) {
        if (className == null) {
            return false;
        }
        
        // Проверяем основные servlet интерфейсы и классы
        return className.contains("javax.servlet") ||
               className.contains("jakarta.servlet") ||
               className.contains("HttpServletRequest") ||
               className.contains("HttpServletResponse") ||
               className.contains("ServletRequest") ||
               className.contains("ServletResponse") ||
               className.contains("ServletContext") ||
               className.contains("FilterChain") ||
               className.contains("RequestDispatcher");
    }

    /**
     * Сериализатор для исключенных пакетов.
     * Сохраняет информацию о типе (@class) для возможности десериализации при тестировании.
     */
    private static class ExcludedPackageSerializer extends StdSerializer<Object> {

        protected ExcludedPackageSerializer(Class<?> targetClass) {
            super(Object.class);
        }

        @Override
        public void serialize(Object value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            if (value == null) {
                gen.writeNull();
                return;
            }
            
            // Безопасное получение информации о классе без вызова методов объекта
            String className = value.getClass().getName();
            String packageName = getPackageNameSafely(value.getClass());
            
            // Сохраняем информацию о типе для возможности десериализации
            gen.writeStartObject();
            gen.writeStringField("@class", className);
            gen.writeStringField("value", "[excluded: " + packageName + "]");
            gen.writeEndObject();
        }

        @Override
        public void serializeWithType(Object value, JsonGenerator gen, SerializerProvider serializers,
                                     TypeSerializer typeSer) throws IOException {
            // Упрощенный подход: используем обычный serialize, который уже правильно работает
            // и записывает @class вручную. Это избегает проблем с контекстом TypeSerializer
            // при сериализации объектов внутри массивов.
            serialize(value, gen, serializers);
        }

        /**
         * Безопасное получение имени пакета без вызова методов, которые могут бросить исключение.
         */
        private static String getPackageNameSafely(Class<?> clazz) {
            try {
                Package pkg = clazz.getPackage();
                if (pkg != null) {
                    return pkg.getName();
                }
            } catch (Exception e) {
                // Игнорируем исключения при получении пакета
            }
            
            // Fallback: извлекаем пакет из имени класса
            String className = clazz.getName();
            int lastDot = className.lastIndexOf('.');
            if (lastDot > 0) {
                return className.substring(0, lastDot);
            }
            return "(default package)";
        }
    }
}
