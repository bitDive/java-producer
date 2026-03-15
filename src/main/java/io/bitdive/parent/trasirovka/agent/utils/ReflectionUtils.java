package io.bitdive.parent.trasirovka.agent.utils;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.afterburner.AfterburnerModule;
import io.bitdive.parent.parserConfig.YamlParserConfig;
import io.bitdive.parent.trasirovka.agent.utils.objectMaperConfig.*;
import io.bitdive.parent.utils.hibernateConfig.HibernateModuleLoader;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Utility class for reflection operations and object serialization.
 * Класс утилит для операций рефлексии и сериализации объектов.
 */
public class ReflectionUtils {

    /**
     * Set of sensitive keywords to mask in serialization.
     * Набор чувствительных ключевых слов для маскировки при сериализации.
     */
    public static final Set<String> SENSITIVE_KEYWORDS = new HashSet<>(Arrays.asList(
            "password", "pass", "secret", "token", "key", "apikey", "auth", "credential"
    ));

    /**
     * Maximum size for collections during serialization.
     * Максимальный размер коллекций при сериализации.
     */
    private static final int MAX_COLLECTION_SIZE = YamlParserConfig
            .getProfilingConfig().getMonitoring().getSerialization().getMaxElementCollection();

    /**
     * Packages to exclude from serialization.
     * Пакеты, исключаемые из сериализации.
     */
    private static final String[] EXCLUDED_PACKAGES = YamlParserConfig
            .getProfilingConfig().getMonitoring().getSerialization().getExcludedPackages();

    /**
     * Indicator for truncated collections.
     * Индикатор для усеченных коллекций.
     */
    private static final String INDICATOR = "...";

    /**
     * ObjectMapper configured for safe serialization with masking and size limits.
     * ObjectMapper, настроенный для безопасной сериализации с маскировкой и ограничениями размера.
     */
    static ObjectMapper mapper = new ObjectMapper();

    static {
        // Configure ObjectMapper for safe serialization
        // Настройка ObjectMapper для безопасной сериализации
        mapper.enable(SerializationFeature.WRITE_SELF_REFERENCES_AS_NULL);
        mapper.getFactory().disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);

        // Register module to limit collection sizes
        // Регистрация модуля для ограничения размеров коллекций
        SimpleModule module = new SimpleModule();
        module.setSerializerModifier(new CollectionSizeLimiter(MAX_COLLECTION_SIZE, INDICATOR));
        mapper.registerModule(module);

        // Register module to mask sensitive data
        // Регистрация модуля для маскировки чувствительных данных
        SimpleModule moduleMask = new SimpleModule();
        moduleMask.setSerializerModifier(new MaskingFilter(SENSITIVE_KEYWORDS));
        mapper.registerModule(moduleMask);

        // Register module to ignore certain packages
        // Регистрация модуля для игнорирования определенных пакетов
        SimpleModule ignoreModule = new SimpleModule();
        ignoreModule.setSerializerModifier(new PackageBasedSerializerModifier(EXCLUDED_PACKAGES));
        mapper.registerModule(ignoreModule);

        // Register additional modules for data handling
        // Регистрация дополнительных модулей для обработки данных
        mapper.registerModule(new FlowDataToPlaceholderModule());

        // Configure deserialization and serialization features
        // Настройка функций десериализации и сериализации
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);

        // Register time and JDK modules
        // Регистрация модулей времени и JDK
        mapper.registerModule(new JavaTimeModule());
        mapper.registerModule(new Jdk8Module());
        mapper.registerModule(new AfterburnerModule());

        // Optionally register Hibernate module if available
        // Опционально регистрировать модуль Hibernate, если доступен
        Optional.ofNullable(HibernateModuleLoader.registerHibernateModule())
                .ifPresent(moduleHibernate ->
                        mapper.registerModule(moduleHibernate)
                );


    }

    /**
     * Converts an object to its string representation using configured ObjectMapper.
     * Преобразует объект в строковое представление с использованием настроенного ObjectMapper.
     *
     * @param obj the object to convert / объект для преобразования
     * @return the string representation or error message / строковое представление или сообщение об ошибке
     */
    public static String objectToString(Object obj) {
        try {
            if (obj == null) {
                return "null";
            }
            String paramAfterSeariles = mapper.writeValueAsString(obj);
            return Optional.ofNullable(paramAfterSeariles)
                    .filter(s -> !s.isEmpty())
                    .filter(s -> !s.equals("null")).orElse("");
        } catch (Exception e) {
            return "[Error: " + e.getMessage() + "]";
        }
    }


    /**
     * Get the value of a field by name from the target object.
     * Получить значение поля с именем fieldName у объекта target.
     *
     * @param target    the object from which to read the field / объект, у которого читаем поле
     * @param fieldName the name of the field (can be private, in superclasses) / имя поля (может быть приватным, в суперклассах)
     * @return the field value / значение поля
     * @throws Exception if the field is not found or access is denied / если поле не найдено или доступ к нему невозможен
     */
    public static Object getFieldValue(Object target, String fieldName) throws Exception {
        if (target == null) {
            throw new IllegalArgumentException("Target object is null");
        }
        Field field = findField(target.getClass(), fieldName);
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    /**
     * Invoke a method without arguments.
     * Вызвать метод без аргументов.
     *
     * @param target     the object on which to invoke the method / объект, у которого вызываем метод
     * @param methodName the name of the method / имя метода
     * @return the result of the method call / результат вызова
     * @throws Exception if the method is not found or an error occurs during invocation / если метод не найден или при вызове произошла ошибка
     */
    public static Object invokeMethod(Object target, String methodName) throws Exception {
        return invokeMethod(target, methodName, new Class<?>[0], new Object[0]);
    }

    /**
     * Invoke a method with parameters.
     * Вызвать метод с параметрами.
     *
     * @param target         the object on which to invoke the method / объект, у которого вызываем метод
     * @param methodName     the name of the method / имя метода
     * @param parameterTypes array of parameter types / массив типов параметров
     * @param args           the method arguments / аргументы метода
     * @return the result of the method call / результат вызова
     * @throws Exception if the method is not found or an error occurs during invocation / если метод не найден или при вызове произошла ошибка
     */
    public static Object invokeMethod(Object target,
                                      String methodName,
                                      Class<?>[] parameterTypes,
                                      Object... args) throws Exception {
        if (target == null) {
            throw new IllegalArgumentException("Target object is null");
        }
        Method method = findMethod(target.getClass(), methodName, parameterTypes);
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    // ----- Helper methods for finding fields and methods in class hierarchy -----
    // ----- Вспомогательные методы поиска поля и метода в иерархии классов -----

    /**
     * Find a field in the class hierarchy.
     * Найти поле в иерархии классов.
     *
     * @param clazz the class to search in / класс для поиска
     * @param name  the field name / имя поля
     * @return the field / поле
     * @throws NoSuchFieldException if the field is not found / если поле не найдено
     */
    private static Field findField(Class<?> clazz, String name) throws NoSuchFieldException {
        Class<?> current = clazz;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException("Field '" + name + "' not found in " + clazz);
    }

    /**
     * Find a method in the class hierarchy.
     * Найти метод в иерархии классов.
     *
     * @param clazz          the class to search in / класс для поиска
     * @param name           the method name / имя метода
     * @param parameterTypes the parameter types / типы параметров
     * @return the method / метод
     * @throws NoSuchMethodException if the method is not found / если метод не найден
     */
    private static Method findMethod(Class<?> clazz,
                                     String name,
                                     Class<?>[] parameterTypes) throws NoSuchMethodException {
        Class<?> current = clazz;
        while (current != null) {
            try {
                return current.getDeclaredMethod(name, parameterTypes);
            } catch (NoSuchMethodException e) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchMethodException("Method '" + name + "' with parameters "
                + java.util.Arrays.toString(parameterTypes)
                + " not found in " + clazz);
    }


}
