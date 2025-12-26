package io.bitdive.parent.message_producer;

import io.bitdive.parent.parserConfig.YamlParserConfig;
import io.bitdive.parent.trasirovka.agent.utils.LoggerStatusContent;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPOutputStream;

/**
 * Легковесный асинхронный writer для мониторинговых данных.
 * Полностью независим от Log4j2/Logback - не конфликтует с логированием клиента.
 */
public class MonitoringFileWriter {
    
    private final String baseFilePath;
    private final String toSendPath;
    private final String serviceName;
    private final long maxFileSizeBytes;

    private volatile Path currentFile;
    private volatile OutputStream currentOutputStream;
    private final AtomicLong currentFileSize = new AtomicLong(0);
    private final AtomicBoolean isRunning = new AtomicBoolean(true);
    
    // Асинхронная очередь для записи
    private final BlockingQueue<String> writeQueue;
    private final ExecutorService writerExecutor;
    private final ScheduledExecutorService rotationScheduler;
    
    private static final int QUEUE_CAPACITY = 10000;
    private static final long MAX_FILE_SIZE_BYTES = 100 * 1024 * 1024; // 100 MB
    
    // Константы для оптимизации (избегаем создания объектов)
    private static final byte[] NEWLINE_BYTES = "\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final int FLUSH_THRESHOLD = 100; // Flush каждые 100 сообщений
    private int messagesSinceFlush = 0;
    
    public MonitoringFileWriter() throws IOException {
        this.baseFilePath = YamlParserConfig.getProfilingConfig().getMonitoring().getDataFile().getPath();
        this.toSendPath = baseFilePath + File.separator + "toSend";
        this.serviceName = YamlParserConfig.getProfilingConfig().getApplication().getServiceName();
        this.maxFileSizeBytes = MAX_FILE_SIZE_BYTES;
        int rotationIntervalSeconds = YamlParserConfig.getProfilingConfig()
                .getMonitoring()
                .getDataFile()
                .getTimerConvertForSend();
        
        // Создаем директории если не существуют
        Files.createDirectories(Paths.get(baseFilePath));
        Files.createDirectories(Paths.get(toSendPath));
        
        // Инициализируем очередь и executor'ы
        this.writeQueue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
        this.writerExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "MonitoringWriter");
            t.setDaemon(true);
            return t;
        });
        this.rotationScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "MonitoringRotation");
            t.setDaemon(true);
            return t;
        });
        
        // ВАЖНО: Проверяем наличие старого файла (после аварийного завершения)
        // и архивируем его перед созданием нового
        Path existingFile = Paths.get(baseFilePath, "monitoringFile.data");
        if (Files.exists(existingFile) && Files.size(existingFile) > 0) {
            archiveFile(existingFile);
            if (LoggerStatusContent.isDebug()) {
                System.out.println("Archived existing monitoring file from previous session");
            }
        }
        
        // Создаем начальный файл
        rotateFile();
        
        // Запускаем writer поток
        writerExecutor.submit(this::writerLoop);
        
        // Запускаем периодическую ротацию по расписанию (cron-like)
        rotationScheduler.scheduleAtFixedRate(
            this::rotateFileIfNeeded,
                rotationIntervalSeconds,
                rotationIntervalSeconds,
            TimeUnit.SECONDS
        );
    }
    
    /**
     * Асинхронная запись сообщения. Не блокирует вызывающий поток.
     */
    public void write(String message) {
        if (!isRunning.get()) {
            return;
        }
        
        // Если очередь полная - пропускаем сообщение (не блокируем)
        boolean offered = writeQueue.offer(message);
        if (!offered && LoggerStatusContent.isDebug()) {
            System.err.println("MonitoringFileWriter: Queue is full, message dropped");
        }
    }
    
    /**
     * Основной цикл записи из очереди в файл
     */
    private void writerLoop() {
        while (isRunning.get() || !writeQueue.isEmpty()) {
            try {
                String message = writeQueue.poll(100, TimeUnit.MILLISECONDS);
                if (message != null) {
                    writeToFile(message);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                if (LoggerStatusContent.isErrorsOrDebug()) {
                    System.err.println("Error writing to monitoring file: " + e.getMessage());
                }
            }
        }
    }
    
    /**
     * Запись в текущий файл с проверкой размера
     * ОПТИМИЗИРОВАНО: минимум создания объектов, редкий flush
     */
    private void writeToFile(String message) throws IOException {
        if (currentOutputStream == null) {
            rotateFile();
        }
        
        // Используем UTF-8 явно для производительности
        byte[] messageBytes = message.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        
        // Записываем сообщение
        currentOutputStream.write(messageBytes);
        // Добавляем перевод строки (переиспользуем константу)
        currentOutputStream.write(NEWLINE_BYTES);
        
        // Обновляем размер
        long totalBytes = messageBytes.length + NEWLINE_BYTES.length;
        long newSize = currentFileSize.addAndGet(totalBytes);
        
        // ВАЖНО: Flush НЕ каждый раз, а периодически для производительности
        messagesSinceFlush++;
        if (messagesSinceFlush >= FLUSH_THRESHOLD) {
            currentOutputStream.flush();
            messagesSinceFlush = 0;
        }
        
        // Проверяем размер файла
        if (newSize >= maxFileSizeBytes) {
            rotateFile();
        }
    }
    
    /**
     * Ротация файла по расписанию
     */
    private void rotateFileIfNeeded() {
        try {
            // Flush перед ротацией для сохранности данных
            if (currentOutputStream != null && currentFileSize.get() > 0) {
                currentOutputStream.flush();
                rotateFile();
            }
        } catch (Exception e) {
            if (LoggerStatusContent.isErrorsOrDebug()) {
                System.err.println("Error during scheduled file rotation: " + e.getMessage());
            }
        }
    }
    
    /**
     * Ротация: закрываем текущий файл, архивируем его и создаем новый
     */
    private synchronized void rotateFile() throws IOException {
        // Финальный flush перед закрытием
        if (currentOutputStream != null) {
            try {
                currentOutputStream.flush();
                currentOutputStream.close();
            } catch (IOException e) {
                if (LoggerStatusContent.isErrorsOrDebug()) {
                    System.err.println("Error closing current file: " + e.getMessage());
                }
            }
        }
        
        // Архивируем предыдущий файл в toSend директорию
        if (currentFile != null && Files.exists(currentFile) && Files.size(currentFile) > 0) {
            archiveFile(currentFile);
        }
        
        // Создаем новый файл
        currentFile = Paths.get(baseFilePath, "monitoringFile.data");
        currentOutputStream = new BufferedOutputStream(
            Files.newOutputStream(currentFile, 
                StandardOpenOption.CREATE, 
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE),
            8192 // buffer size
        );
        currentFileSize.set(0);
        messagesSinceFlush = 0; // Сбрасываем счётчик
    }
    
    /**
     * Архивирование файла в gzip
     */
    private void archiveFile(Path sourceFile) {
        try {
            // Добавлены миллисекунды для избежания конфликтов имен при быстрой ротации
            String timestamp = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS").format(new Date());
            String archiveName = String.format("data-%s-%s.data.gz", timestamp, serviceName);
            Path archivePath = Paths.get(toSendPath, archiveName);
            
            // Сжимаем файл в gzip
            try (InputStream in = Files.newInputStream(sourceFile);
                 GZIPOutputStream gzipOut = new GZIPOutputStream(
                     new BufferedOutputStream(Files.newOutputStream(archivePath), 8192))) {
                
                byte[] buffer = new byte[8192];
                int len;
                while ((len = in.read(buffer)) > 0) {
                    gzipOut.write(buffer, 0, len);
                }
            }
            
            if (LoggerStatusContent.isDebug()) {
                System.out.println("Archived monitoring file: " + archiveName);
            }
            
        } catch (IOException e) {
            if (LoggerStatusContent.isErrorsOrDebug()) {
                System.err.println("Error archiving file: " + e.getMessage());
            }
        }
    }
    
    /**
     * Корректная остановка writer'а с дожиданием записи всех сообщений
     */
    public void shutdown() {
        isRunning.set(false);
        
        // Останавливаем ротацию
        rotationScheduler.shutdown();
        try {
            if (!rotationScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                rotationScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            rotationScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        // Ждем завершения записи всех сообщений
        writerExecutor.shutdown();
        try {
            if (!writerExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                if (LoggerStatusContent.isDebug()) {
                    System.out.println("Writer did not finish in time, forcing shutdown...");
                }
                writerExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            writerExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        // Закрываем текущий файл
        if (currentOutputStream != null) {
            try {
                currentOutputStream.close();
                // Архивируем последний файл если он не пустой
                if (currentFile != null && Files.exists(currentFile) && Files.size(currentFile) > 0) {
                    archiveFile(currentFile);
                }
            } catch (IOException e) {
                if (LoggerStatusContent.isErrorsOrDebug()) {
                    System.err.println("Error closing file on shutdown: " + e.getMessage());
                }
            }
        }
        
        if (LoggerStatusContent.isDebug()) {
            System.out.println("MonitoringFileWriter shutdown complete");
        }
    }
}

