package io.bitdive.parent.message_producer;

import io.bitdive.parent.parserConfig.YamlParserConfig;
import io.bitdive.parent.trasirovka.agent.utils.LoggerStatusContent;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
    private final ExecutorService archiveExecutor; // Отдельный executor для архивирования
    
    private static final int QUEUE_CAPACITY = 10000;
    private static final long MAX_FILE_SIZE_BYTES = 100 * 1024 * 1024; // 100 MB
    
    // Константы для оптимизации (избегаем создания объектов)
    private static final byte[] NEWLINE_BYTES = "\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final int FLUSH_THRESHOLD = 200; // Flush каждые 200 сообщений (увеличено для снижения CPU)
    private static final int BATCH_SIZE = 50; // Обрабатываем до 50 сообщений за раз
    private static final int BUFFER_SIZE = 16384; // Увеличенный буфер (16KB вместо 8KB)
    private static final long POLL_TIMEOUT_MS = 100; // Базовый timeout
    private static final long IDLE_POLL_TIMEOUT_MS = 500; // Увеличенный timeout когда очередь пустая
    
    // Потокобезопасный форматтер для даты (вместо SimpleDateFormat)
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = 
        DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss-SSS");
    private int messagesSinceFlush = 0;
    private volatile boolean queueWasEmpty = true; // Флаг для адаптивного timeout
    
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
        // Отдельный executor для архивирования (не блокирует основной поток записи)
        this.archiveExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "MonitoringArchive");
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
     * ОПТИМИЗИРОВАНО: batch обработка сообщений и адаптивный timeout для снижения нагрузки на CPU
     */
    private void writerLoop() {
        java.util.List<String> batch = new java.util.ArrayList<>(BATCH_SIZE);
        
        while (isRunning.get() || !writeQueue.isEmpty()) {
            try {
                // Адаптивный timeout: больше когда очередь пустая (меньше опросов CPU)
                long timeout = queueWasEmpty ? IDLE_POLL_TIMEOUT_MS : POLL_TIMEOUT_MS;
                
                // Собираем batch сообщений для обработки
                batch.clear();
                String firstMessage = writeQueue.poll(timeout, TimeUnit.MILLISECONDS);
                
                if (firstMessage != null) {
                    batch.add(firstMessage);
                    queueWasEmpty = false;
                    
                    // Собираем дополнительные сообщения без блокировки (drain)
                    writeQueue.drainTo(batch, BATCH_SIZE - 1);
                    
                    // Записываем все сообщения из batch
                    for (String message : batch) {
                        try {
                            writeToFile(message);
                        } catch (Exception e) {
                            if (LoggerStatusContent.isErrorsOrDebug()) {
                                System.err.println("Error writing message to file: " + e.getMessage());
                            }
                        }
                    }
                } else {
                    queueWasEmpty = true;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                if (LoggerStatusContent.isErrorsOrDebug()) {
                    System.err.println("Error in writer loop: " + e.getMessage());
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
        // Сохраняем ссылки для архивирования
        OutputStream oldStream = currentOutputStream;
        Path oldFile = currentFile;
        // Сохраняем размер файла ДО сброса счетчика
        long oldFileSize = currentFileSize.get();
        
        // Закрываем старый поток с гарантированным закрытием
        if (oldStream != null) {
            try {
                oldStream.flush();
            } catch (IOException e) {
                if (LoggerStatusContent.isErrorsOrDebug()) {
                    System.err.println("Error flushing current file: " + e.getMessage());
                }
            } finally {
                try {
                    oldStream.close();
                } catch (IOException e) {
                    if (LoggerStatusContent.isErrorsOrDebug()) {
                        System.err.println("Error closing current file: " + e.getMessage());
                    }
                }
            }
        }
        
        // Архивируем предыдущий файл асинхронно (не блокируем основной поток записи)
        if (oldFile != null && oldFileSize > 0) {
            final Path fileToArchive = oldFile;
            // Асинхронное архивирование в отдельном executor
            archiveExecutor.submit(() -> {
                try {
                    // Проверяем существование только один раз
                    if (Files.exists(fileToArchive)) {
                        archiveFile(fileToArchive);
                    }
                } catch (Exception e) {
                    if (LoggerStatusContent.isErrorsOrDebug()) {
                        System.err.println("Error in async archiving: " + e.getMessage());
                    }
                }
            });
        }
        
        // Создаем новый файл
        try {
            currentFile = Paths.get(baseFilePath, "monitoringFile.data");
            currentOutputStream = new BufferedOutputStream(
                Files.newOutputStream(currentFile, 
                    StandardOpenOption.CREATE, 
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE),
                BUFFER_SIZE // Увеличенный буфер для снижения системных вызовов
            );
            currentFileSize.set(0);
            messagesSinceFlush = 0; // Сбрасываем счётчик
        } catch (IOException e) {
            // Если не удалось создать новый файл, сбрасываем ссылку
            currentOutputStream = null;
            currentFile = null;
            throw e;
        }
    }
    
    /**
     * Архивирование файла в gzip
     * ОПТИМИЗИРОВАНО: использует потокобезопасный DateTimeFormatter вместо SimpleDateFormat
     */
    private void archiveFile(Path sourceFile) {
        try {
            // Используем потокобезопасный форматтер (переиспользуем константу)
            // Добавлены миллисекунды для избежания конфликтов имен при быстрой ротации
            String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMATTER);
            String archiveName = String.format("data-%s-%s.data.gz", timestamp, serviceName);
            Path archivePath = Paths.get(toSendPath, archiveName);
            
            // Сжимаем файл в gzip с использованием try-with-resources для гарантированного закрытия
            try (InputStream in = Files.newInputStream(sourceFile);
                 BufferedOutputStream bufferedOut = new BufferedOutputStream(
                     Files.newOutputStream(archivePath), BUFFER_SIZE);
                 GZIPOutputStream gzipOut = new GZIPOutputStream(bufferedOut)) {
                
                // Переиспользуем буфер для минимизации аллокаций (увеличенный размер)
                byte[] buffer = new byte[BUFFER_SIZE];
                int len;
                while ((len = in.read(buffer)) > 0) {
                    gzipOut.write(buffer, 0, len);
                }
                // Явный flush для гарантии записи всех данных
                gzipOut.finish();
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
        
        // Останавливаем executor архивирования
        archiveExecutor.shutdown();
        try {
            if (!archiveExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                archiveExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            archiveExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        // Закрываем текущий файл с гарантированным закрытием
        if (currentOutputStream != null) {
            OutputStream streamToClose = currentOutputStream;
            Path fileToArchive = currentFile;
            
            try {
                streamToClose.flush();
            } catch (IOException e) {
                if (LoggerStatusContent.isErrorsOrDebug()) {
                    System.err.println("Error flushing file on shutdown: " + e.getMessage());
                }
            } finally {
                try {
                    streamToClose.close();
                } catch (IOException e) {
                    if (LoggerStatusContent.isErrorsOrDebug()) {
                        System.err.println("Error closing file on shutdown: " + e.getMessage());
                    }
                }
            }
            
            // Архивируем последний файл если он не пустой (после закрытия потока)
            // Используем синхронное архивирование при shutdown для гарантии завершения
            if (fileToArchive != null) {
                try {
                    if (Files.exists(fileToArchive) && Files.size(fileToArchive) > 0) {
                        archiveFile(fileToArchive);
                    }
                } catch (IOException e) {
                    if (LoggerStatusContent.isErrorsOrDebug()) {
                        System.err.println("Error archiving file on shutdown: " + e.getMessage());
                    }
                }
            }
        }
        
        if (LoggerStatusContent.isDebug()) {
            System.out.println("MonitoringFileWriter shutdown complete");
        }
    }
}

