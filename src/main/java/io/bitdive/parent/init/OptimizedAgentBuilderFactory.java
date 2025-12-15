package io.bitdive.parent.init;

import net.bytebuddy.agent.builder.AgentBuilder;

import static net.bytebuddy.matcher.ElementMatchers.*;

/**
 * Фабрика для создания оптимизированных AgentBuilder с расширенным ignore
 * списком.
 * Это ключевая оптимизация - игнорирование большого количества системных и
 * библиотечных классов
 * значительно ускоряет загрузку приложения.
 */
public class OptimizedAgentBuilderFactory {

    /**
     * Создает стандартный AgentBuilder с оптимизированным ignore списком.
     * Используется для агентов БЕЗ retransformation.
     */
    public static AgentBuilder createOptimizedStandardBuilder() {
        return new AgentBuilder.Default()
                .ignore(getOptimizedIgnoreMatcher())
                .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE) // Не инициализируем классы
                .with(AgentBuilder.TypeStrategy.Default.REDEFINE);
    }

    /**
     * Создает AgentBuilder с retransformation и оптимизированным ignore списком.
     * Используется для большинства агентов.
     */
    public static AgentBuilder createOptimizedRetransformationBuilder() {
        return new AgentBuilder.Default()
                .ignore(getOptimizedIgnoreMatcher())
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
                .with(AgentBuilder.TypeStrategy.Default.REDEFINE);
    }

    /**
     * Минимальный ignore matcher - ТОЛЬКО JDK классы.
     * Любые дополнительные фильтры должны применяться на уровне конкретных агентов!
     */
    private static net.bytebuddy.matcher.ElementMatcher.Junction<net.bytebuddy.description.type.TypeDescription> getOptimizedIgnoreMatcher() {
        return none()
                // Игнорируем только JDK классы - это безопасно для всех агентов
                .or(nameStartsWith("java."))
                .or(nameStartsWith("javax."))
                .or(nameStartsWith("jdk."))
                .or(nameStartsWith("sun."))
                .or(nameStartsWith("com.sun."));
    }

    /**
     * Создает matcher для игнорирования конкретных пакетов приложения.
     * Полезно, если в приложении есть пакеты, которые точно не нужно мониторить.
     */
    public static net.bytebuddy.matcher.ElementMatcher.Junction<net.bytebuddy.description.type.TypeDescription> getApplicationSpecificIgnoreMatcher(
            String... packagesToIgnore) {
        net.bytebuddy.matcher.ElementMatcher.Junction<net.bytebuddy.description.type.TypeDescription> matcher = none();
        for (String pkg : packagesToIgnore) {
            matcher = matcher.or(nameStartsWith(pkg));
        }
        return matcher;
    }
}
