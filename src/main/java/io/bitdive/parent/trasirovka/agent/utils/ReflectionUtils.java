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

public class ReflectionUtils {

    public static final Set<String> SENSITIVE_KEYWORDS = new HashSet<>(Arrays.asList(
            "password", "pass", "secret", "token", "key", "apikey", "auth", "credential"
    ));

    private static final int MAX_COLLECTION_SIZE = YamlParserConfig
            .getProfilingConfig().getMonitoring().getSerialization().getMaxElementCollection();

    private static final String[] EXCLUDED_PACKAGES = YamlParserConfig
            .getProfilingConfig().getMonitoring().getSerialization().getExcludedPackages();
    private static final String INDICATOR = "...";

    static ObjectMapper mapper = new ObjectMapper();

    static {


        mapper.enable(SerializationFeature.WRITE_SELF_REFERENCES_AS_NULL);
        mapper.getFactory().disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);

        SimpleModule module = new SimpleModule();
        module.setSerializerModifier(new CollectionSizeLimiter(MAX_COLLECTION_SIZE, INDICATOR));
        mapper.registerModule(module);

        SimpleModule moduleMask = new SimpleModule();
        moduleMask.setSerializerModifier(new MaskingFilter(SENSITIVE_KEYWORDS));
        mapper.registerModule(moduleMask);

        SimpleModule ignoreModule = new SimpleModule();
        ignoreModule.setSerializerModifier(new PackageBasedSerializerModifier(EXCLUDED_PACKAGES));
        mapper.registerModule(ignoreModule);

        mapper.registerModule(new FlowDataToPlaceholderModule());

        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);

        mapper.registerModule(new JavaTimeModule());
        mapper.registerModule(new Jdk8Module());
        mapper.registerModule(new AfterburnerModule());

        Optional.ofNullable(HibernateModuleLoader.registerHibernateModule())
                .ifPresent(moduleHibernate ->
                        mapper.registerModule(moduleHibernate)
                );


    }

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
     * Получить значение поля с именем fieldName у объекта target.
     *
     * @param target    объект, у которого читаем поле
     * @param fieldName имя поля (может быть приватным, в суперклассах)
     * @return значение поля
     * @throws Exception если поле не найдено или доступ к нему невозможен
     */
    public static Object getFieldValue(Object target, String fieldName) throws Exception {
        if (target == null) {
            throw new IllegalArgumentException("Target object is null");
        }
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    /**
     * Вызвать метод без аргументов.
     *
     * @param target     объект, у которого вызываем метод
     * @param methodName имя метода
     * @return результат вызова
     * @throws Exception если метод не найден или при вызове произошла ошибка
     */
    public static Object invokeMethod(Object target, String methodName) throws Exception {
        return invokeMethod(target, methodName, new Class<?>[0], new Object[0]);
    }

    /**
     * Вызвать метод с параметрами.
     *
     * @param target         объект, у которого вызываем метод
     * @param methodName     имя метода
     * @param parameterTypes массив типов параметров
     * @param args           аргументы метода
     * @return результат вызова
     * @throws Exception если метод не найден или при вызове произошла ошибка
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

    // ----- Вспомогательные методы поиска поля и метода в иерархии классов -----

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
