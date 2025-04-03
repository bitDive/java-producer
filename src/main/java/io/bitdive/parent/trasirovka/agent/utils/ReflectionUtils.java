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
import io.bitdive.parent.trasirovka.agent.utils.objectMaperConfig.ByteArrayToPlaceholderModule;
import io.bitdive.parent.trasirovka.agent.utils.objectMaperConfig.CollectionSizeLimiter;
import io.bitdive.parent.trasirovka.agent.utils.objectMaperConfig.MaskingFilter;
import io.bitdive.parent.trasirovka.agent.utils.objectMaperConfig.PackageBasedSerializerModifier;
import io.bitdive.parent.utils.hibernateConfig.HibernateModuleLoader;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

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

        mapper.registerModule(new ByteArrayToPlaceholderModule());


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
}
