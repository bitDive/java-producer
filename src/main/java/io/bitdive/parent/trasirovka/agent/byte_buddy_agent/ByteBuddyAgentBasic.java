package io.bitdive.parent.trasirovka.agent.byte_buddy_agent;


import io.bitdive.parent.parserConfig.YamlParserConfig;
import io.bitdive.parent.trasirovka.agent.method_advice.BasicInterceptor;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.NamedElement;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;

import static net.bytebuddy.matcher.ElementMatchers.*;


public class ByteBuddyAgentBasic {
    public static ElementMatcher.Junction<TypeDescription> getSpringComponentMatcher() {
        boolean monitoringOnlySpringComponent = YamlParserConfig.getProfilingConfig()
                .getMonitoring()
                .getMonitoringOnlySpringComponent();

        if (monitoringOnlySpringComponent) {
            return ElementMatchers.isAnnotatedWith(nameContains("org.springframework"));
        } else {
            return ElementMatchers.any();
        }
    }

    public static <T extends NamedElement> ElementMatcher.Junction<T> getApplicationPackedScanner(String[] infixes) {
        ElementMatcher.Junction<T> matcher = none();
        for (String infix : infixes) {
            matcher = matcher.or(nameStartsWith(infix));
        }
        return matcher;
    }

    private static ElementMatcher.Junction<TypeDescription> getExcludedSuperTypeMatcher() {
        String[] excludedPackages = {
                "org.springframework.security.",
                "org.springframework.web.filter."
        };

        ElementMatcher.Junction<TypeDescription> matcher = none();
        for (String pkg : excludedPackages) {
            matcher = matcher.or(ElementMatchers.hasSuperType(ElementMatchers.nameStartsWith(pkg)));
        }
        return matcher;
    }

    public static void init() {
        new AgentBuilder.Default()
                .type(
                        getApplicationPackedScanner(YamlParserConfig.getProfilingConfig().getApplication().getPackedScanner())
                                .and(getSpringComponentMatcher())
                                .and(ElementMatchers.not(ElementMatchers.isEnum()))
                                .and(ElementMatchers.not(ElementMatchers.isAnnotatedWith(ElementMatchers.named("io.bitdive.parent.anotations.NotMonitoring"))))
                                .and(ElementMatchers.not(ElementMatchers.nameEndsWith("Configuration")))
                                .and(ElementMatchers.not(ElementMatchers.nameEndsWith("RefreshScope")))
                                .and(ElementMatchers.not(ElementMatchers.nameEndsWith("ConfigurationProperties")))
                                .and(ElementMatchers.not(ElementMatchers.isSynthetic()))
                                .and(ElementMatchers.not(ElementMatchers.nameMatches(".*\\$\\$.*")))
                                .and(ElementMatchers.not(nameContains("CGLIB")))
                                .and(ElementMatchers.not(nameContains("ByteBuddy")))
                                .and(ElementMatchers.not(nameContains("$")))
                                .and(ElementMatchers.not(getExcludedSuperTypeMatcher()))
                )
                .transform((builder, typeDescription, classLoader, module, sd) ->
                        builder.method(ElementMatchers.any()
                                        .and(ElementMatchers.not(ElementMatchers.isAbstract()))
                                        .and(ElementMatchers.not(ElementMatchers.isAnnotatedWith(ElementMatchers.nameEndsWith("Bean"))))
                                        .and(ElementMatchers.not(ElementMatchers.isAnnotatedWith(ElementMatchers.nameEndsWith("ExceptionHandler"))))
                                        .and(ElementMatchers.not(ElementMatchers.nameMatches(".*\\$.*")))
                                        .and(ElementMatchers.not(ElementMatchers.isSynthetic()))
                                        .and(ElementMatchers.not(ElementMatchers.isDeclaredBy(Object.class)))
                                        .and(ElementMatchers.not(ElementMatchers.isDeclaredBy(Enum.class)))
                                        .and(ElementMatchers.not(ElementMatchers.isAnnotatedWith(ElementMatchers.nameEndsWith("PostConstruct"))))

                                )
                                .intercept(Advice.to(BasicInterceptor.class))
                )
                .installOnByteBuddyAgent();
    }
}
