package io.bitdive.parent.trasirovka.agent.utils;


import io.bitdive.parent.parserConfig.LogLevelEnum;
import io.bitdive.parent.parserConfig.ProfilingConfig;
import io.bitdive.parent.parserConfig.YamlParserConfig;

import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class LoggerStatusContent {

    private static final ScheduledExecutorService monitoringDelayScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "monitoring-activation-delay");
                thread.setDaemon(true);
                return thread;
            });

    private static final AtomicBoolean monitoringDelayScheduled = new AtomicBoolean(false);
    private static volatile boolean monitoringDelayElapsed = true;

    public static boolean getEnabledProfile() {
        if (!monitoringDelayElapsed) {
            return true;
        }
        return Optional.ofNullable(YamlParserConfig.getProfilingConfig())
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

    public static void initMonitoringDelay(Duration delay) {
        if (delay == null || delay.isZero() || delay.isNegative()) {
            monitoringDelayElapsed = true;
            return;
        }

        if (monitoringDelayScheduled.compareAndSet(false, true)) {
            monitoringDelayElapsed = false;
            monitoringDelayScheduler.schedule(() -> {
                monitoringDelayElapsed = true;
                monitoringDelayScheduler.shutdown();
            }, delay.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    /**
     * КРИТИЧНО: Принудительная остановка scheduler если он еще не завершен.
     * Полезно при shutdown приложения.
     */
    public static void shutdownScheduler() {
        if (monitoringDelayScheduler != null && !monitoringDelayScheduler.isShutdown()) {
            monitoringDelayScheduler.shutdownNow();
            monitoringDelayElapsed = true;
        }
    }
}
