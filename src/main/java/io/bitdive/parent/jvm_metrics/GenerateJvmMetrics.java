package io.bitdive.parent.jvm_metrics;

import io.bitdive.parent.parserConfig.ProfilingConfig;
import io.bitdive.parent.parserConfig.YamlParserConfig;
import io.bitdive.parent.trasirovka.agent.utils.LoggerStatusContent;

import java.io.File;
import java.io.IOException;
import java.net.Proxy;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static io.bitdive.parent.utils.UtilsDataConvert.initilizationProxy;
import static io.bitdive.parent.utils.UtilsDataConvert.sendFile;

public class GenerateJvmMetrics {
    private static ScheduledExecutorService scheduler;
    private static Path logDirectoryPath;
    private static DateTimeFormatter formatter;
    private static Proxy proxy;


    public static void init() {
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


    private static void startLogging() {
        Runnable logTask = GenerateJvmMetrics::collectMetrics;
        scheduler.scheduleAtFixedRate(logTask, 0, 20, TimeUnit.SECONDS);
        if (LoggerStatusContent.isErrorsOrDebug())
            System.out.println("Start logging metrics every minute to a directory: " + logDirectoryPath.toAbsolutePath());
    }


    public void stopLogging() {
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
            JvmMetricsResponse response = new JvmMetricsResponse();
            response.setHeapMemoryUsed(JvmMetricsService.getUsedHeapMemory());
            response.setNonHeapMemoryUsed(JvmMetricsService.getNonHeapMemoryUsed());
            response.setThreadCount(JvmMetricsService.getThreadCount());
            response.setDaemonThreadCount(JvmMetricsService.getDaemonThreadCount());
            response.setTotalMemory(JvmMetricsService.getTotalMemory());
            response.setFreeMemory(JvmMetricsService.getFreeMemory());
            response.setMaxMemory(JvmMetricsService.getMaxMemory());
            response.setAvailableProcessors(JvmMetricsService.getAvailableProcessors());
            response.setSystemCpuLoadPercentage(JvmMetricsService.getSystemCpuLoad());
            response.setProcessCpuLoadPercentage(JvmMetricsService.getProcessCpuLoad());

            response.setTotalSwapSpace(JvmMetricsService.getTotalSwapSpace());
            response.setFreeSwapSpace(JvmMetricsService.getFreeSwapSpace());

            response.setDiskMetrics(JvmMetricsService.getDiskMetrics());

            response.setModuleName(YamlParserConfig.getProfilingConfig().getApplication().getModuleName());
            response.setServiceName(YamlParserConfig.getProfilingConfig().getApplication().getServiceName());
            response.setCreatedMetric(OffsetDateTime.now());

            writeMetricsToFile(response);
        } catch (Exception e) {
            if (LoggerStatusContent.isErrorsOrDebug())
                System.err.println("Failed to write metrics to file: " + e.getMessage());

        }
    }

    private static void writeMetricsToFile(JvmMetricsResponse response) {
        try {
            String timestamp = LocalDateTime.now().format(formatter);
            String fileName = String.format("jvm_metrics_%s.BitDiveData", timestamp);
            Path filePath = logDirectoryPath.resolve(fileName);

            Files.write(filePath, response.toString().getBytes(), StandardOpenOption.CREATE_NEW);
            if (LoggerStatusContent.isDebug())
                System.out.println("Metrics write in file: " + filePath.toAbsolutePath());
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
