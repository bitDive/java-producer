package io.bitdive.parent.trasirovka.agent.utils;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.afterburner.AfterburnerModule;
import com.fasterxml.jackson.databind.jsontype.impl.StdTypeResolverBuilder;
import io.bitdive.parent.parserConfig.YamlParserConfig;
import io.bitdive.parent.trasirovka.agent.utils.objectMaperConfig.*;
import io.bitdive.parent.utils.hibernateConfig.HibernateModuleLoader;
import lombok.Getter;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.List;

import static com.fasterxml.jackson.databind.MapperFeature.USE_ANNOTATIONS;

public class ReflectionUtils {
    private static final String INDICATOR = "...";

    @Getter
    static ObjectMapper mapper;
    /**
     * A SAFE JSON parser for untrusted payloads (e.g., HTTP responses).
     * Important: must NOT have default typing enabled to avoid unsafe polymorphic deserialization.
     */
    private static final ObjectMapper SAFE_JSON_PARSER = JsonMapper.builder()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .build();
    
    /**
     * Fallback mapper without Afterburner for cases when Afterburner causes IllegalAccessError.
     * Created lazily when needed.
     */
    private static ObjectMapper fallbackMapperWithoutAfterburner;


    public static void init(List<StdTypeResolverBuilder> builders ,
                            int MAX_COLLECTION_SIZE ,
                            String[] EXCLUDED_PACKAGES,
                            List<SimpleModule> simpleModuleList

    ) {

        mapper= JsonMapper.builder().disable(USE_ANNOTATIONS).build();

        AfterburnerModule ab =new AfterburnerModule();
        ab.setUseValueClassLoader(true);
        mapper.registerModule(ab);

        SpringOptionalSerializers.tryRegisterSpringSortSerializer(mapper);

        mapper.setVisibility(
                mapper.getVisibilityChecker()
                        .withFieldVisibility(JsonAutoDetect.Visibility.ANY)
                        .withGetterVisibility(JsonAutoDetect.Visibility.NONE)
                        .withIsGetterVisibility(JsonAutoDetect.Visibility.NONE)
                        .withSetterVisibility(JsonAutoDetect.Visibility.NONE)
                        .withCreatorVisibility(JsonAutoDetect.Visibility.ANY)
        );


        mapper.enable(SerializationFeature.WRITE_SELF_REFERENCES_AS_NULL);
        mapper.getFactory().disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);

        SimpleModule module = new SimpleModule();
        module.setSerializerModifier(new CollectionSizeLimiter(MAX_COLLECTION_SIZE, INDICATOR));
        mapper.registerModule(module);

        simpleModuleList.forEach((simpleModule) -> {
            mapper.registerModule(simpleModule);
        });



        SimpleModule moduleMask = new SimpleModule();
        moduleMask.setSerializerModifier(new MaskingFilter(YamlParserConfig.getProfilingConfig().getMonitoring().getSerialization().getSensitiveKeyWords()));
        mapper.registerModule(moduleMask);

        SimpleModule ignoreModule = new SimpleModule();
        ignoreModule.setSerializerModifier(new PackageBasedSerializerModifier(EXCLUDED_PACKAGES));
        mapper.registerModule(ignoreModule);

        SimpleModule LambdaSerializer = new SimpleModule();
        LambdaSerializer.setSerializerModifier(new LambdaSerializerModifier());
        mapper.registerModule(LambdaSerializer);

        // Модификатор для исключения serialVersionUID из сериализации
        // (предотвращает InaccessibleObjectException в Java 9+ модулях)
        SimpleModule serialVersionUidExclusion = new SimpleModule();
        serialVersionUidExclusion.setSerializerModifier(new SerialVersionUidExclusionModifier());
        mapper.registerModule(serialVersionUidExclusion);


        SimpleModule imageModule = new SimpleModule();

        imageModule.addSerializer(BufferedImage.class, new RenderedImagePlaceholderSerializer());
        imageModule.addSerializer(RenderedImage.class, new RenderedImagePlaceholderSerializer());
        imageModule.addSerializer(Image.class, new AwtImagePlaceholderSerializer());
        mapper.registerModule(imageModule);

        // Модуль для компактной сериализации исключений (Throwable)
        mapper.registerModule(new ThrowableSerializerModule());

        mapper.registerModule(new FlowDataToPlaceholderModule());

        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);

        mapper.registerModule(new JavaTimeModule());
        mapper.registerModule(new Jdk8Module());


        Optional.ofNullable(HibernateModuleLoader.registerHibernateModule())
                .ifPresent(moduleHibernate ->
                        mapper.registerModule(moduleHibernate)
                );

        mapper.activateDefaultTyping(
                LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY
        );

        builders.forEach(builder ->
        {
            mapper.setDefaultTyping(builder);
        });

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
        } catch (Error e) {
            // Afterburner module may fail with IllegalAccessError when trying to access
            // fields across class loader boundaries. Fallback to serialization without Afterburner.
            // Also catch other Error types that might occur during serialization.
            try {
                ObjectMapper fallbackMapper = getOrCreateFallbackMapper();
                String paramAfterSeariles = fallbackMapper.writeValueAsString(obj);
                return Optional.ofNullable(paramAfterSeariles)
                        .filter(s -> !s.isEmpty())
                        .filter(s -> !s.equals("null")).orElse("");
            } catch (Throwable fallbackException) {
                // If fallback also fails, return error message
                String errorMsg = e.getMessage();
                String fallbackMsg = fallbackException.getMessage();
                return "[Error: " + (errorMsg != null ? errorMsg : e.getClass().getSimpleName()) + 
                       " (fallback failed: " + (fallbackMsg != null ? fallbackMsg : fallbackException.getClass().getSimpleName()) + ")]";
            }
        } catch (Exception e) {
            return "[Error: " + e.getMessage() + "]";
        }
    }

    /**
     * Gets or creates a fallback ObjectMapper without Afterburner module for cases when
     * Afterburner fails due to IllegalAccessError (e.g., class loader issues).
     * The mapper is cached for reuse.
     */
    private static ObjectMapper getOrCreateFallbackMapper() {
        if (fallbackMapperWithoutAfterburner != null) {
            return fallbackMapperWithoutAfterburner;
        }
        
        synchronized (ReflectionUtils.class) {
            if (fallbackMapperWithoutAfterburner != null) {
                return fallbackMapperWithoutAfterburner;
            }
            fallbackMapperWithoutAfterburner = createMapperWithoutAfterburner();
            return fallbackMapperWithoutAfterburner;
        }
    }
    
    /**
     * Creates a fallback ObjectMapper without Afterburner module.
     */
    private static ObjectMapper createMapperWithoutAfterburner() {
        ObjectMapper fallbackMapper = JsonMapper.builder().disable(USE_ANNOTATIONS).build();
        
        fallbackMapper.setVisibility(
                fallbackMapper.getVisibilityChecker()
                        .withFieldVisibility(JsonAutoDetect.Visibility.ANY)
                        .withGetterVisibility(JsonAutoDetect.Visibility.NONE)
                        .withIsGetterVisibility(JsonAutoDetect.Visibility.NONE)
                        .withSetterVisibility(JsonAutoDetect.Visibility.NONE)
                        .withCreatorVisibility(JsonAutoDetect.Visibility.ANY)
        );
        
        fallbackMapper.enable(SerializationFeature.WRITE_SELF_REFERENCES_AS_NULL);
        fallbackMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        fallbackMapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
        fallbackMapper.registerModule(new JavaTimeModule());
        fallbackMapper.registerModule(new Jdk8Module());
        
        // Copy default typing configuration from main mapper if available
        if (mapper != null) {
            try {
                fallbackMapper.activateDefaultTyping(
                        LaissezFaireSubTypeValidator.instance,
                        ObjectMapper.DefaultTyping.NON_FINAL,
                        JsonTypeInfo.As.PROPERTY
                );
            } catch (Exception ignored) {
                // If default typing setup fails, continue without it
            }
        }
        
        return fallbackMapper;
    }

    /**
     * If {@code raw} looks like JSON object/array, parses it with a SAFE mapper and re-serializes with our
     * typed/masked/limited {@link #mapper}. This makes {@code @class} appear in the resulting JSON where applicable.
     *
     * <p>Non-JSON strings are returned as-is.</p>
     */
    public static String tryNormalizeJsonStringWithClass(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return "";
        }

        // We only attempt for JSON object/array to avoid turning primitives/strings into quoted JSON.
        char first = trimmed.charAt(0);
        if (first != '{' && first != '[') {
            return raw;
        }

        // Avoid excessive overhead on very large payloads.
        if (trimmed.length() > 300_000) {
            return raw;
        }

        try {
            Object parsed = SAFE_JSON_PARSER.readValue(trimmed, Object.class);
            return objectToString(parsed);
        } catch (Exception ignored) {
            return raw;
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
