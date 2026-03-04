package io.bitdive.parent.message_producer;

import io.bitdive.parent.parserConfig.ProfilingConfig;
import io.bitdive.parent.parserConfig.YamlParserConfig;
import io.bitdive.parent.trasirovka.agent.utils.LoggerStatusContent;

import java.io.IOException;
import java.net.Proxy;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;

import static io.bitdive.parent.utils.UtilsDataConvert.initilizationProxy;
import static io.bitdive.parent.utils.UtilsDataConvert.sendFile;

/**
 * Сервис для периодического сканирования и отправки архивированных файлов на сервер.
 * Полностью независим от Log4j2 - переработанная логика из CustomHttpAppender.
 */
public class FileUploadService {
    
    private final String filePath;
    private final Proxy proxy;
    private final Integer fileStorageTime;
    
    private final ScheduledExecutorService scheduler;
    private final ExecutorService uploadPool;
    private final AtomicBoolean isSending;
    
    private static final int CONCURRENT_UPLOADS = 5;
    private static final int QUEUE_CAPACITY = 10;
    private static final long SCAN_INTERVAL_SECONDS = 15;
    
    public FileUploadService() {
        ProfilingConfig config = YamlParserConfig.getProfilingConfig();
        
        this.filePath = config.getMonitoring().getDataFile().getPath() + "/toSend";
        this.fileStorageTime = config.getMonitoring().getDataFile().getFileStorageTime();
        
        // Инициализация прокси
        String proxyHost = Optional.ofNullable(config.getMonitoring().getSendFiles().getServerConsumer())
                .map(ProfilingConfig.MonitoringConfig.MonitoringSendFilesConfig.ServerConsumerConfig::getProxy)
                .map(ProfilingConfig.MonitoringConfig.MonitoringSendFilesConfig.ServerConsumerConfig.ProxyConfig::getHost)
                .orElse(null);
        
        String proxyPort = Optional.ofNullable(config.getMonitoring().getSendFiles().getServerConsumer())
                .map(ProfilingConfig.MonitoringConfig.MonitoringSendFilesConfig.ServerConsumerConfig::getProxy)
                .map(ProfilingConfig.MonitoringConfig.MonitoringSendFilesConfig.ServerConsumerConfig.ProxyConfig::getPort)
                .map(Object::toString)
                .orElse(null);
        
        String proxyUserName = Optional.ofNullable(config.getMonitoring().getSendFiles().getServerConsumer())
                .map(ProfilingConfig.MonitoringConfig.MonitoringSendFilesConfig.ServerConsumerConfig::getProxy)
                .map(ProfilingConfig.MonitoringConfig.MonitoringSendFilesConfig.ServerConsumerConfig.ProxyConfig::getUsername)
                .orElse(null);
        
        String proxyPassword = Optional.ofNullable(config.getMonitoring().getSendFiles().getServerConsumer())
                .map(ProfilingConfig.MonitoringConfig.MonitoringSendFilesConfig.ServerConsumerConfig::getProxy)
                .map(ProfilingConfig.MonitoringConfig.MonitoringSendFilesConfig.ServerConsumerConfig.ProxyConfig::getPassword)
                .orElse(null);
        
        this.proxy = initilizationProxy(proxyHost, proxyPort, proxyUserName, proxyPassword);
        
        this.isSending = new AtomicBoolean(false);
        
        // Thread pool для параллельной загрузки файлов
        this.uploadPool = new ThreadPoolExecutor(
                CONCURRENT_UPLOADS,
                CONCURRENT_UPLOADS,
                0L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                new ThreadFactory() {
                    private int counter = 0;
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "FileUpload-" + counter++);
                        t.setDaemon(true);
                        return t;
                    }
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        
        // Scheduler для периодического сканирования
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "FileUploadScheduler");
            t.setDaemon(true);
            return t;
        });
        
        // Запускаем периодическое сканирование
        scheduler.scheduleAtFixedRate(
                this::scanAndSendFiles,
                0,
                SCAN_INTERVAL_SECONDS,
                TimeUnit.SECONDS
        );
    }
    
    /**
     * Сканирование директории и отправка .gz файлов
     */
    private void scanAndSendFiles() {
        // Проверка на enabled profile
        if (LoggerStatusContent.getEnabledProfile()) {
            return;
        }
        
        // Атомарная проверка - только один поток может сканировать одновременно
        if (!isSending.compareAndSet(false, true)) {
            return;
        }
        
        try {
            Path dir = Paths.get(filePath);
            
            // Проверяем существование директории
            if (!Files.exists(dir)) {
                return;
            }
            
            List<Path> filesToSend = new ArrayList<>();
            
            // Находим все .gz файлы
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.gz")) {
                for (Path entry : stream) {
                    // Проверяем, что файл существует и не пустой
                    if (Files.exists(entry) && Files.size(entry) > 0) {
                        filesToSend.add(entry);
                    }
                }
            }
            
            if (LoggerStatusContent.isDebug() && !filesToSend.isEmpty()) {
                System.out.println("FileUploadService: Found " + filesToSend.size() + " files to upload");
            }
            
            // Отправляем каждый файл в отдельной задаче
            // ИСПРАВЛЕНО: обработка RejectedExecutionException для предотвращения потери задач
            for (Path file : filesToSend) {
                try {
                    uploadPool.submit(createUploadTask(file));
                } catch (RejectedExecutionException e) {
                    // Если пул переполнен, выполняем задачу синхронно в текущем потоке
                    // чтобы избежать потери файлов для отправки
                    if (LoggerStatusContent.isDebug()) {
                        System.err.println("Upload pool rejected task, executing synchronously: " + file.getFileName());
                    }
                    try {
                        createUploadTask(file).run();
                    } catch (Exception taskEx) {
                        if (LoggerStatusContent.isDebug()) {
                            System.err.println("Error executing upload task synchronously: " + taskEx.getMessage());
                        }
                    }
                }
            }
            
        } catch (Exception e) {
            if (LoggerStatusContent.isDebug()) {
                System.err.println("Error scanning files: " + e.getMessage());
            }
        } finally {
            isSending.set(false);
        }
    }
    
    /**
     * Создание задачи для загрузки файла
     */
    private Runnable createUploadTask(Path file) {
        return () -> {
            try {
                // Проверяем валидность gzip архива
                if (!isGzipValid(file)) {
                    // Если архив битый - проверяем возраст и удаляем если старый
                    deleteIfOld(file);
                    return;
                }
                
                // Пытаемся отправить файл
                boolean uploaded = sendFile(file.toFile(), proxy, "uploadFileData");
                
                if (uploaded) {
                    // Успешно отправлен - удаляем
                    Files.deleteIfExists(file);
                    if (LoggerStatusContent.isDebug()) {
                        System.out.println("Successfully uploaded and deleted: " + file.getFileName());
                    }
                } else {
                    // Не удалось отправить - проверяем возраст
                    deleteIfOld(file);
                }
                
            } catch (Exception e) {
                if (LoggerStatusContent.isDebug()) {
                    System.err.println("Error uploading file " + file.getFileName() + ": " + e.getMessage());
                }
            }
        };
    }
    
    /**
     * Проверка валидности gzip архива
     */
    private boolean isGzipValid(Path file) {
        try (GZIPInputStream gis = new GZIPInputStream(Files.newInputStream(file))) {
            byte[] buffer = new byte[8192];
            while (gis.read(buffer) != -1) {
                // Просто читаем весь поток для проверки
            }
            return true;
        } catch (IOException e) {
            if (LoggerStatusContent.isDebug()) {
                System.out.println("GZIP validation failed for " + file.getFileName() + ": " + e.getMessage());
            }
            return false;
        }
    }
    
    /**
     * Удаление файла если он старше fileStorageTime
     */
    private void deleteIfOld(Path file) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
            long ageMinutes = (System.currentTimeMillis() - attrs.creationTime().toMillis()) / 60_000;
            
            if (ageMinutes > fileStorageTime) {
                Files.deleteIfExists(file);
                if (LoggerStatusContent.isDebug()) {
                    System.out.println("Deleted old file: " + file.getFileName() + " (age: " + ageMinutes + " min)");
                }
            }
        } catch (IOException e) {
            if (LoggerStatusContent.isDebug()) {
                System.err.println("Error checking/deleting old file: " + e.getMessage());
            }
        }
    }
    
    /**
     * Корректная остановка сервиса
     */
    public void shutdown() {
        // Останавливаем scheduler
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        // Останавливаем upload pool
        uploadPool.shutdown();
        try {
            if (!uploadPool.awaitTermination(30, TimeUnit.SECONDS)) {
                if (LoggerStatusContent.isDebug()) {
                    System.out.println("Upload pool did not terminate in time, forcing shutdown...");
                }
                uploadPool.shutdownNow();
                
                if (!uploadPool.awaitTermination(10, TimeUnit.SECONDS)) {
                    if (LoggerStatusContent.isErrorsOrDebug()) {
                        System.err.println("Upload pool did not terminate after forced shutdown");
                    }
                }
            }
            
            if (LoggerStatusContent.isDebug()) {
                System.out.println("FileUploadService shutdown complete");
            }
            
        } catch (InterruptedException e) {
            uploadPool.shutdownNow();
            Thread.currentThread().interrupt();
            if (LoggerStatusContent.isErrorsOrDebug()) {
                System.err.println("Upload pool shutdown was interrupted");
            }
        }
    }
}

