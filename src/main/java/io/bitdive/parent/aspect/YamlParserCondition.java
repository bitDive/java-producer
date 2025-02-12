package io.bitdive.parent.aspect;

import io.bitdive.parent.parserConfig.YamlParserConfig;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

public class YamlParserCondition implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return YamlParserConfig.isWork();
    }
}