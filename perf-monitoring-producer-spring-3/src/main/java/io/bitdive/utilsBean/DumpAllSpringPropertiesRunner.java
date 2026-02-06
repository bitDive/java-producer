package io.bitdive.utilsBean;

import io.bitdive.parent.dto.ApplicationEnvironment;
import io.bitdive.parent.parserConfig.YamlParserConfig;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

@Component
public class DumpAllSpringPropertiesRunner implements ApplicationRunner {

    private final ConfigurableEnvironment env;

    public DumpAllSpringPropertiesRunner(ConfigurableEnvironment env) {
        this.env = env;
    }

    private static final Pattern SENSITIVE = Pattern.compile(
            "(?i)(?:^|[._-])(?:password|passwd|secret|token|api[_-]?key|jwt|private[_-]?key)(?:$|[._-])"
    );

    private static final List<String> ignoreEnv=Arrays.asList("Path","java.class.path","java.library.path");

    @Override
    public void run(ApplicationArguments args) {
        Set<String> names = new TreeSet<>();
        Map<String, String> mapEnvironment = new TreeMap<>();
        for (PropertySource<?> ps : env.getPropertySources()) {
            if (!(ps instanceof EnumerablePropertySource)) {
                continue;
            }
            EnumerablePropertySource<?> eps = (EnumerablePropertySource<?>) ps;
            names.addAll(Arrays.asList(eps.getPropertyNames()));
        }



        for (String name : names) {
            if (!ignoreEnv.contains(name)) {
                mapEnvironment.put(name, maskIfSensitive(name, env.getProperty(name)));
            }
        }

        YamlParserConfig.setApplicationEnvironment(ApplicationEnvironment.builder()
                .moduleName(YamlParserConfig.getProfilingConfig().getApplication().getModuleName())
                .serviceName(YamlParserConfig.getProfilingConfig().getApplication().getServiceName())
                .activeProfiles(Arrays.asList(env.getActiveProfiles()))
                .serviceNodeUUID(YamlParserConfig.getUUIDService())
                .environmentApplication(mapEnvironment)
                .build()
        );


        names.clear();
    }

    private String maskIfSensitive(String key, String value) {
        if (value == null) return "null";
        if (key != null && SENSITIVE.matcher(key).find()) return "******";
        return value;
    }
}
