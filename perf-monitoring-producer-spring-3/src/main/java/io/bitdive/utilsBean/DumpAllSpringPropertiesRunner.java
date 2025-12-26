package io.bitdive.utilsBean;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.*;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

@Component
public class DumpAllSpringPropertiesRunner implements ApplicationRunner {

    private final ConfigurableEnvironment env;

    public DumpAllSpringPropertiesRunner(ConfigurableEnvironment env) {
        this.env = env;
    }

    private static final Pattern SENSITIVE =
            Pattern.compile(".*(password|passwd|secret|token|apikey|api_key|private|key|jwt).*",
                    Pattern.CASE_INSENSITIVE);

    @Override
    public void run(ApplicationArguments args) {
        Set<String> names = new TreeSet<>();

        for (PropertySource<?> ps : env.getPropertySources()) {
            if (ps instanceof EnumerablePropertySource<?> eps) {
                names.addAll(Arrays.asList(eps.getPropertyNames()));
            }
        }

        System.out.println("=== Active profiles: " + Arrays.toString(env.getActiveProfiles()) + " ===");
        System.out.println("=== Spring properties dump (" + names.size() + " keys) ===");

        for (String name : names) {
            String value = env.getProperty(name);
            System.out.printf("%s = %s%n", name, maskIfSensitive(name, value));
        }
    }

    private String maskIfSensitive(String key, String value) {
        if (value == null) return "null";
        if (SENSITIVE.matcher(key).matches()) return "******";
        return value;
    }
}
