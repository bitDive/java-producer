package io.bitdive.java_agent.byte_buddy_agent;


import io.bitdive.java_agent.method_advice.BasicInterceptor;
import io.bitdive.parent.parserConfig.YamlParserConfig;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;


public class ByteBuddyAgentBasic {

    public static ElementMatcher.Junction<TypeDescription> getSpringComponentMatcher() {
        boolean monitoringOnlySpringComponent = YamlParserConfig.getProfilingConfig()
                .getMonitoring()
                .getMonitoringOnlySpringComponent();

        if (monitoringOnlySpringComponent) {
            return ElementMatchers.isAnnotatedWith(ElementMatchers.nameContains("org.springframework"));
        } else {
            return ElementMatchers.any();
        }
    }

    public static void init() {
        new AgentBuilder.Default()
                .type(ElementMatchers.nameContains(YamlParserConfig.getProfilingConfig().getApplication().getPackedScanner())
                        .and( getSpringComponentMatcher())
                        .and(ElementMatchers.not(ElementMatchers.isAnnotatedWith(ElementMatchers.named("com.loggerAndTraser.anotations.NotMonitoring"))))
                        .and(ElementMatchers.not(ElementMatchers.isAnnotatedWith(ElementMatchers.nameEndsWith("Configuration"))))
                        .and(ElementMatchers.not(ElementMatchers.isAnnotatedWith(ElementMatchers.nameEndsWith("RefreshScope"))))
                        .and(ElementMatchers.not(ElementMatchers.isAnnotatedWith(ElementMatchers.nameEndsWith("ConfigurationProperties"))))
                        .and(ElementMatchers.not(ElementMatchers.isSynthetic()))
                        .and(ElementMatchers.not(ElementMatchers.nameMatches(".*\\$\\$.*")))
                        .and(ElementMatchers.not(ElementMatchers.nameContains("CGLIB")))
                        .and(ElementMatchers.not(ElementMatchers.nameContains("ByteBuddy")))
                        .and(ElementMatchers.not(ElementMatchers.nameContains("$")))
                )
                .transform((builder, typeDescription, classLoader, module, sd) ->
                        builder.method(ElementMatchers.any()
                                        .and(ElementMatchers.not(ElementMatchers.isAbstract()))
                                        .and(ElementMatchers.not(ElementMatchers.isAnnotatedWith(ElementMatchers.nameEndsWith("Bean"))))
                                        .and(ElementMatchers.not(ElementMatchers.isAnnotatedWith(ElementMatchers.nameEndsWith("ExceptionHandler"))))
                                        .and(ElementMatchers.not(ElementMatchers.named("hashCode")))
                                        .and(ElementMatchers.not(ElementMatchers.nameMatches(".*\\$.*")))
                                        .and(ElementMatchers.not(ElementMatchers.isSynthetic()))
                                )
                                .intercept(Advice.to(BasicInterceptor.class)))
                .installOnByteBuddyAgent();
    }
}
