package io.bitdive.jvm_metrics;

import io.bitdive.parent.parserConfig.ProfilingConfig;
import io.bitdive.parent.parserConfig.YamlParserConfig;
import io.bitdive.parent.trasirovka.agent.utils.LoggerStatusContent;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.FileDescriptorMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.core.instrument.binder.system.UptimeMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.io.File;
import java.io.IOException;
import java.net.Proxy;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

import static io.bitdive.parent.utils.UtilsDataConvert.initilizationProxy;
import static io.bitdive.parent.utils.UtilsDataConvert.sendFile;

public class GenerateJvmMetrics {
    private static ScheduledExecutorService scheduler;
    private static Path logDirectoryPath;
    private static DateTimeFormatter formatter;
    private static Proxy proxy;

    private static MeterRegistry meterRegistry;

    private static long crcValue = 0;

    public static String buildConfigString() {
        var monitoring = YamlParserConfig.getProfilingConfig().getMonitoring();

        // 1. Включено ли логирование
        String enabled = Boolean.toString(monitoring.isEnabled());

        // 2. Путь к директории логов
        String logDir = monitoring.getDataFile().getPath() + File.separator + "toSendMetrics";

        // 3. Время хранения файлов (в минутах)
        String ttl = Long.toString(monitoring.getDataFile().getFileStorageTime());

        // 4. Параметры прокси
        var proxyConfig = Optional.ofNullable(monitoring.getSendFiles().getServerConsumer())
                .map(ProfilingConfig.MonitoringConfig.MonitoringSendFilesConfig.ServerConsumerConfig::getProxy)
                .orElse(null);

        String proxyHost = proxyConfig != null ? proxyConfig.getHost() : "";
        String proxyPort = proxyConfig != null && proxyConfig.getPort() != null
                ? proxyConfig.getPort().toString() : "";
        String proxyUser = proxyConfig != null ? proxyConfig.getUsername() : "";
        String proxyPassword = proxyConfig != null ? proxyConfig.getPassword() : "";

        // Собираем все через разделитель '|'
        return String.join("|",
                Objects.toString(enabled, ""),
                Objects.toString(logDir, ""),
                Objects.toString(ttl, ""),
                Objects.toString(proxyHost, ""),
                Objects.toString(proxyPort, ""),
                Objects.toString(proxyUser, ""),
                Objects.toString(proxyPassword, "")
        );
    }

    public static void init() {
        if (LoggerStatusContent.getEnabledProfile()) {
            stopLogging();
            crcValue = 0;
            return;
        }
        CRC32 crc = new CRC32();
        crc.update(buildConfigString().getBytes(StandardCharsets.UTF_8));
        boolean isReload = crcValue != crc.getValue();
        crcValue = crc.getValue();

        if (isReload) {
            stopLogging();

            String filePath = YamlParserConfig.getProfilingConfig().getMonitoring().getDataFile().getPath() + File.separator + "toSendMetrics";
            scheduler = Executors.newSingleThreadScheduledExecutor();
            logDirectoryPath = Paths.get(filePath);
            formatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

            String proxyHost = Optional.ofNullable(YamlParserConfig.getProfilingConfig().getMonitoring().getSendFiles().getServerConsumer())
                    .map(ProfilingConfig.MonitoringConfig.MonitoringSendFilesConfig.ServerConsumerConfig::getProxy)
                    .map(ProfilingConfig.MonitoringConfig.MonitoringSendFilesConfig.ServerConsumerConfig.ProxyConfig::getHost)
                    .orElse(null);
            String proxyPort = Optional.ofNullable(YamlParserConfig.getProfilingConfig().getMonitoring().getSendFiles().getServerConsumer())
                    .map(ProfilingConfig.MonitoringConfig.MonitoringSendFilesConfig.ServerConsumerConfig::getProxy)
                    .map(ProfilingConfig.MonitoringConfig.MonitoringSendFilesConfig.ServerConsumerConfig.ProxyConfig::getPort)
                    .map(Object::toString)
                    .orElse(null);
            String proxyUserName = Optional.ofNullable(YamlParserConfig.getProfilingConfig().getMonitoring().getSendFiles().getServerConsumer())
                    .map(ProfilingConfig.MonitoringConfig.MonitoringSendFilesConfig.ServerConsumerConfig::getProxy)
                    .map(ProfilingConfig.MonitoringConfig.MonitoringSendFilesConfig.ServerConsumerConfig.ProxyConfig::getUsername)
                    .orElse(null);
            String proxyPassword = Optional.ofNullable(YamlParserConfig.getProfilingConfig().getMonitoring().getSendFiles().getServerConsumer())
                    .map(ProfilingConfig.MonitoringConfig.MonitoringSendFilesConfig.ServerConsumerConfig::getProxy)
                    .map(ProfilingConfig.MonitoringConfig.MonitoringSendFilesConfig.ServerConsumerConfig.ProxyConfig::getPassword)
                    .orElse(null);

            proxy = initilizationProxy(proxyHost, proxyPort, proxyUserName, proxyPassword);
            try {
                if (!Files.exists(logDirectoryPath)) {
                    Files.createDirectories(logDirectoryPath);
                    if (LoggerStatusContent.isDebug())
                        System.out.println("A directory for logs has been created: " + logDirectoryPath.toAbsolutePath());
                }
            } catch (IOException e) {
                if (LoggerStatusContent.isErrorsOrDebug())
                    System.err.println("Failed to create log directory: " + filePath + " \n Error: " + e);
            }
            startLogging();
        }
    }


    private static void startLogging() {
        try {
            meterRegistry = new SimpleMeterRegistry();
            new JvmMemoryMetrics().bindTo(meterRegistry);
            new JvmGcMetrics().bindTo(meterRegistry);
            new JvmThreadMetrics().bindTo(meterRegistry);
            new ProcessorMetrics().bindTo(meterRegistry);
            new UptimeMetrics().bindTo(meterRegistry);
            new FileDescriptorMetrics().bindTo(meterRegistry);
            meterRegistry.counter("bitDive.metrics").increment(5);

            Runnable logTask = GenerateJvmMetrics::collectMetrics;
            scheduler.scheduleAtFixedRate(logTask, 0, 30, TimeUnit.SECONDS);
        } catch (Exception e) {
            if (LoggerStatusContent.isErrorsOrDebug())
                System.out.println("Start logging metrics every minute to a directory: " + logDirectoryPath.toAbsolutePath());
        }
    }


    private static void stopLogging() {
        if (scheduler == null) return;
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
            if (LoggerStatusContent.isDebug())
                System.out.println("Metric logging has stopped.");
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
            if (LoggerStatusContent.isErrorsOrDebug())
                System.err.println("Metrics logging was interrupted.");
        }
    }

    private static void collectMetrics() {
        try {
            writeMetricsToFile(new MetricsService(meterRegistry));
        } catch (Exception e) {
            if (LoggerStatusContent.isErrorsOrDebug())
                System.err.println("Failed to write metrics to file: " + e.getMessage());

        }
    }

    private static void writeMetricsToFile(MetricsService metricsService) {
        try {
            String timestamp = LocalDateTime.now().format(formatter);
            String fileName = String.format("jvm_metrics_%s.BitDiveData", timestamp);
            Path filePath = logDirectoryPath.resolve(fileName);

            Files.write(filePath, metricsService.sendMetrics().getBytes(), StandardOpenOption.CREATE_NEW);
            scanAndSendFiles();
        } catch (IOException e) {
            if (LoggerStatusContent.isErrorsOrDebug())
                System.err.println("Failed to write metrics to file: " + e.getMessage());

        }
    }


    private static void scanAndSendFiles() {
        try {
            List<Path> filesToSend = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(logDirectoryPath, "*.BitDiveData")) {
                for (Path entry : stream) {
                    filesToSend.add(entry);
                }
            }
            for (Path file : filesToSend) {
                boolean success = sendFile(file.toFile(), proxy, "uploadFileJVMMetrics");
                if (success) {
                    Files.delete(file);
                } else {
                    BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
                    FileTime creationTime = attrs.creationTime();
                    long ageInMinutes = (System.currentTimeMillis() - creationTime.toMillis()) / (60 * 1000);

                    if (ageInMinutes > YamlParserConfig.getProfilingConfig().getMonitoring().getDataFile().getFileStorageTime()) {
                        Files.delete(file);
                    }
                }
            }
        } catch (Exception e) {
            if (LoggerStatusContent.isDebug()) System.out.println("error scan file " + e);
        }
    }


}
