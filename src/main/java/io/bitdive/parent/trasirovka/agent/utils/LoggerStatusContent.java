package io.bitdive.parent.trasirovka.agent.utils;


import io.bitdive.parent.parserConfig.LogLevelEnum;
import io.bitdive.parent.parserConfig.ProfilingConfig;
import io.bitdive.parent.parserConfig.YamlParserConfig;

import java.util.Arrays;
import java.util.Optional;

public class LoggerStatusContent {

    public static boolean getEnabledProfile() {
        return
                Optional.ofNullable(YamlParserConfig.getProfilingConfig())
                        .map(ProfilingConfig::getMonitoring)
                        .map(monitoringConfig -> !monitoringConfig.isEnabled())
                        .orElse(true);
    }

    public static boolean isErrorsOrDebug(){
        if (getEnabledProfile()) return false;
        return Arrays.asList(LogLevelEnum.ERRORS, LogLevelEnum.DEBUG).contains(
                Optional.ofNullable(YamlParserConfig.getProfilingConfig())
                        .map(ProfilingConfig::getMonitoring)
                        .map(ProfilingConfig.MonitoringConfig::getLogLevel).orElse(LogLevelEnum.INFO)

        );
    }

    public static boolean isErrors (){
        if (getEnabledProfile()) return false;
        return YamlParserConfig.getProfilingConfig().getMonitoring().getLogLevel()==LogLevelEnum.ERRORS;
    }

    public static boolean isInfo (){
        if (getEnabledProfile()) return false;
        return YamlParserConfig.getProfilingConfig().getMonitoring().getLogLevel()==LogLevelEnum.INFO;
    }
    public static boolean isDebug (){
        if (getEnabledProfile()) return false;
        return YamlParserConfig.getProfilingConfig().getMonitoring().getLogLevel()==LogLevelEnum.DEBUG;
    }
}
