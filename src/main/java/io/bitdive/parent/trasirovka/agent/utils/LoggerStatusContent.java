package io.bitdive.parent.trasirovka.agent.utils;


import io.bitdive.parent.parserConfig.LogLevelEnum;
import io.bitdive.parent.parserConfig.ProfilingConfig;
import io.bitdive.parent.parserConfig.YamlParserConfig;

import java.util.Arrays;
import java.util.Optional;

public class LoggerStatusContent {

    public static boolean isErrorsOrDebug(){
        return Arrays.asList(LogLevelEnum.ERRORS, LogLevelEnum.DEBUG).contains(
                Optional.ofNullable(YamlParserConfig.getProfilingConfig())
                        .map(ProfilingConfig::getMonitoring)
                        .map(ProfilingConfig.MonitoringConfig::getLogLevel).orElse(LogLevelEnum.INFO)

        );
    }

    public static boolean isErrors (){
        return YamlParserConfig.getProfilingConfig().getMonitoring().getLogLevel()==LogLevelEnum.ERRORS;
    }

    public static boolean isInfo (){
        return YamlParserConfig.getProfilingConfig().getMonitoring().getLogLevel()==LogLevelEnum.INFO;
    }
    public static boolean isDebug (){
        return YamlParserConfig.getProfilingConfig().getMonitoring().getLogLevel()==LogLevelEnum.DEBUG;
    }
}
