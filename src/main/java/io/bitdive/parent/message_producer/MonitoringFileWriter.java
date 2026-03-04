package io.bitdive.parent.message_producer;

import io.bitdive.parent.parserConfig.YamlParserConfig;
import io.bitdive.parent.trasirovka.agent.utils.LoggerStatusContent;
import org.jctools.queues.MpscArrayQueue;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
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
    private final MpscArrayQueue<String> writeQueue;
    private final ExecutorService writerExecutor;
    private final ScheduledExecutorService rotationScheduler;
    private final ExecutorService archiveExecutor; // Отдельный executor для архивирования

    /**
     * Для MPSC ring-buffer лучше использовать степень двойки.
     * Важно: увеличение capacity повышает "буфер" при пиках, но удерживает больше объектов в памяти.
     */
    private static final int QUEUE_CAPACITY = 1 << 16; // 65536
    private static final long MAX_FILE_SIZE_BYTES = 100 * 1024 * 1024; // 100 MB

    // Константы для оптимизации (избегаем создания объектов)
    private static final byte[] NEWLINE_BYTES = "\n".getBytes(StandardCharsets.UTF_8);
    /**
     * Flush сильно тормозит под нагрузкой. Для throughput делаем flush реже:
     * - при опустошении очереди (чтобы "дотолкать" данные на диск в период простоя)
     * - при ротации
     * - при shutdown
     */
    private static final int FLUSH_THRESHOLD = 200; // Flush каждые N сообщений (компромисс durability/throughput)
    private static final int BATCH_SIZE = 500; // Больше batch -> меньше lock/park операций на очереди
    private static final int BUFFER_SIZE = 16384; // Увеличенный буфер (16KB вместо 8KB)
    private static final long POLL_TIMEOUT_MS = 100; // Базовый timeout
    private static final long IDLE_POLL_TIMEOUT_MS = 500; // Увеличенный timeout когда очередь пустая

    // ИСПРАВЛЕНО: Переиспользуемый буфер для архивирования (ThreadLocal для потокобезопасности)
    // Предотвращает создание нового буфера при каждом архивировании
    private static final ThreadLocal<byte[]> ARCHIVE_BUFFER = ThreadLocal.withInitial(() -> new byte[BUFFER_SIZE]);

    // ИСПРАВЛЕНО: Ограничение на очередь архивирования для предотвращения утечки памяти
    private static final int ARCHIVE_QUEUE_CAPACITY = 10; // Максимум 10 задач архивирования в очереди

    // Потокобезопасный форматтер для даты (вместо SimpleDateFormat)
    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
        DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss-SSS");
    private int messagesSinceFlush = 0;
    private volatile boolean queueWasEmpty = true; // Флаг для адаптивного timeout

    // Метрики очереди (для отладки пиковых нагрузок)
    private final AtomicLong enqueuedCount = new AtomicLong(0);
    private final AtomicLong dequeuedCount = new AtomicLong(0);
    private final AtomicLong droppedCount = new AtomicLong(0);

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
        this.writeQueue = new MpscArrayQueue<>(QUEUE_CAPACITY);
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
        // ИСПРАВЛЕНО: Отдельный executor для архивирования с ограниченной очередью
        // Предотвращает накопление задач архивирования и утечку памяти
        // Используем ThreadPoolExecutor с ограниченной очередью вместо SingleThreadExecutor
        this.archiveExecutor = new ThreadPoolExecutor(
            1, 1, // Один поток для архивирования
            0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(ARCHIVE_QUEUE_CAPACITY), // Ограниченная очередь
            r -> {
                Thread t = new Thread(r, "MonitoringArchive");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy() // При переполнении выполняем синхронно
        );

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

        // Если очередь полная - пропускаем сообщение (drop), не блокируем producers
        boolean offered = writeQueue.offer(message);
        if (offered) {
            enqueuedCount.incrementAndGet();
        } else {
            droppedCount.incrementAndGet();
            if (LoggerStatusContent.isDebug()) {
                System.err.println("MonitoringFileWriter: Queue is full, message dropped");
            }
        }
    }

    /**
     * Основной цикл записи из очереди в файл
     * ОПТИМИЗИРОВАНО: batch обработка сообщений и адаптивный timeout для снижения нагрузки на CPU
     * ИСПРАВЛЕНО: переиспользование batch списка для предотвращения лишних аллокаций
     */
    private void writerLoop() {
        while (isRunning.get() || !writeQueue.isEmpty()) {
            try {
                // MPSC очередь неблокирующая -> дреним батчами
                int drained = 0;
                String message;
                while (drained < BATCH_SIZE && (message = writeQueue.poll()) != null) {
                    drained++;
                    dequeuedCount.incrementAndGet();
                    try {
                        writeToFile(message);
                    } catch (Exception e) {
                        if (LoggerStatusContent.isErrorsOrDebug()) {
                            System.err.println("Error writing message to file: " + e.getMessage());
                        }
                    }
                }

                if (drained == 0) {
                    // Очередь пуста: снижаем нагрузку на CPU (лёгкий backoff)
                    // Адаптивный backoff: после активной работы спим меньше, при длительном простое — больше
                    long parkMs = queueWasEmpty ? IDLE_POLL_TIMEOUT_MS : POLL_TIMEOUT_MS;
                    queueWasEmpty = true;
                    LockSupport.parkNanos(parkMs * 1_000_000L);
                    continue;
                }

                queueWasEmpty = false;

                // Flush только когда очередь опустела (дорогая операция)
                if (writeQueue.isEmpty()) {
                    try {
                        if (currentOutputStream != null) {
                            currentOutputStream.flush();
                        }
                    } catch (IOException e) {
                        if (LoggerStatusContent.isErrorsOrDebug()) {
                            System.err.println("Error flushing on idle: " + e.getMessage());
                        }
                    }
                }

            } catch (Exception e) {
                if (LoggerStatusContent.isErrorsOrDebug()) {
                    System.err.println("Error in writer loop: " + e.getMessage());
                }
            }
        }
    }

    public long getDroppedCount() {
        return droppedCount.get();
    }

    public long getEnqueuedCount() {
        return enqueuedCount.get();
    }

    public long getDequeuedCount() {
        return dequeuedCount.get();
    }

    /**
     * Запись в текущий файл с проверкой размера
     * ОПТИМИЗИРОВАНО: минимум создания объектов, редкий flush
     * ИСПРАВЛЕНО: фильтрация null байтов для предотвращения ошибок парсинга JSON
     * ИСПРАВЛЕНО: принудительный flush для пустых сообщений и периодический flush
     */
    private void writeToFile(String message) throws IOException {
        if (currentOutputStream == null) {
            rotateFile();
        }

        // Валидация и очистка сообщения от null байтов и недопустимых символов
        if (message == null) {
            message = "";
        }

        // Используем UTF-8 явно для производительности
        byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);

        // ОПТИМИЗИРОВАНО: фильтрация null байтов дорогая (лишний проход по массиву).
        // Поэтому делаем её только если в строке реально встречается '\0'.
        if (message.indexOf('\0') >= 0) {
            // Фильтруем null байты (\x00) которые могут вызвать ошибки при парсинге JSON
            // Это важно для предотвращения ошибки "invalid character '\x00'"
            messageBytes = filterNullBytes(messageBytes);
        }

        // Пропускаем запись если сообщение полностью пустое после фильтрации
        if (messageBytes.length == 0 && message.isEmpty()) {
            return;
        }

        // Записываем сообщение
        currentOutputStream.write(messageBytes);
        // Добавляем перевод строки (переиспользуем константу)
        currentOutputStream.write(NEWLINE_BYTES);

        // Обновляем размер
        long totalBytes = messageBytes.length + NEWLINE_BYTES.length;
        long newSize = currentFileSize.addAndGet(totalBytes);

        // ВАЖНО: Flush периодически для производительности и гарантии записи
        // Flush каждые FLUSH_THRESHOLD сообщений для гарантии записи на диск
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
     * Фильтрация null байтов из массива байтов
     * Предотвращает ошибки парсинга JSON на стороне сервера
     */
    private byte[] filterNullBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return bytes;
        }

        // Подсчитываем количество null байтов
        int nullCount = 0;
        for (byte b : bytes) {
            if (b == 0) {
                nullCount++;
            }
        }

        // Если null байтов нет - возвращаем исходный массив
        if (nullCount == 0) {
            return bytes;
        }

        // Создаем новый массив без null байтов
        byte[] filtered = new byte[bytes.length - nullCount];
        int index = 0;
        for (byte b : bytes) {
            if (b != 0) {
                filtered[index++] = b;
            }
        }

        return filtered;
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
     * Ротация: закрываем текущий файл, переименовываем его и создаем новый
     * ОПТИМИЗИРОВАНО: переименование вместо синхронного архивирования для снижения блокировок
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

        // Переименовываем файл для последующего асинхронного архивирования
        // Это позволяет быстро создать новый файл без блокировки на архивирование
        Path renamedFile = null;
        if (oldFile != null && oldFileSize > 0) {
            try {
                if (Files.exists(oldFile)) {
                    long actualFileSize = Files.size(oldFile);
                    if (actualFileSize > 0) {
                        // Создаем уникальное имя для переименованного файла
                        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMATTER);
                        String renamedFileName = String.format("monitoringFile-%s.data", timestamp);
                        renamedFile = Paths.get(baseFilePath, renamedFileName);

                        // Переименовываем файл (быстрая атомарная операция)
                        Files.move(oldFile, renamedFile);

                        // ИСПРАВЛЕНО: Асинхронно архивируем переименованный файл с защитой от переполнения очереди
                        // ThreadPoolExecutor с CallerRunsPolicy выполнит синхронно при переполнении очереди
                        final Path fileToArchive = renamedFile;
                        try {
                            archiveExecutor.submit(() -> {
                                try {
                                    if (LoggerStatusContent.isDebug()) {
                                        System.out.println("Starting async archiving of: " + fileToArchive.getFileName());
                                    }
                                    archiveFile(fileToArchive);
                                    // Удаляем переименованный файл после успешного архивирования
                                    Files.deleteIfExists(fileToArchive);
                                    if (LoggerStatusContent.isDebug()) {
                                        System.out.println("Completed archiving and deleted temp file: " + fileToArchive.getFileName());
                                    }
                                } catch (Exception e) {
                                    if (LoggerStatusContent.isErrorsOrDebug()) {
                                        System.err.println("Error in async archiving: " + e.getMessage());
                                        e.printStackTrace();
                                    }
                                    // ИСПРАВЛЕНО: предотвращение утечки памяти - удаляем файл даже при ошибке архивирования
                                    // чтобы избежать накопления переименованных файлов
                                    try {
                                        Files.deleteIfExists(fileToArchive);
                                        if (LoggerStatusContent.isDebug()) {
                                            System.out.println("Deleted temp file after archiving error: " + fileToArchive.getFileName());
                                        }
                                    } catch (IOException deleteEx) {
                                        if (LoggerStatusContent.isErrorsOrDebug()) {
                                            System.err.println("Failed to delete temp file after archiving error: " + deleteEx.getMessage());
                                        }
                                    }
                                }
                            });
                        } catch (RejectedExecutionException e) {
                            // ИСПРАВЛЕНО: если executor переполнен или закрыт, архивируем синхронно
                            // Это не должно происходить с CallerRunsPolicy, но оставляем для надежности
                            if (LoggerStatusContent.isErrorsOrDebug()) {
                                System.err.println("Archive executor rejected task, archiving synchronously: " + e.getMessage());
                            }
                            try {
                                archiveFile(fileToArchive);
                                Files.deleteIfExists(fileToArchive);
                            } catch (Exception archiveEx) {
                                if (LoggerStatusContent.isErrorsOrDebug()) {
                                    System.err.println("Error in synchronous archiving: " + archiveEx.getMessage());
                                }
                                // Удаляем файл даже при ошибке
                                try {
                                    Files.deleteIfExists(fileToArchive);
                                } catch (IOException deleteEx) {
                                    if (LoggerStatusContent.isErrorsOrDebug()) {
                                        System.err.println("Failed to delete temp file: " + deleteEx.getMessage());
                                    }
                                }
                            }
                        }
                    } else if (LoggerStatusContent.isDebug()) {
                        System.out.println("Skipping rotation of empty file: " + oldFile);
                        // Удаляем пустой файл
                        Files.deleteIfExists(oldFile);
                    }
                }
            } catch (Exception e) {
                if (LoggerStatusContent.isErrorsOrDebug()) {
                    System.err.println("Error renaming file during rotation: " + e.getMessage());
                }
            }
        }

        // Создаем новый файл сразу (не ждем архивирования)
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
     * ИСПРАВЛЕНО: гарантирует запись только реально прочитанных байтов (без null байтов из буфера)
     * ИСПРАВЛЕНО: проверка размера файла и валидация перед архивированием
     */
    private void archiveFile(Path sourceFile) {
        try {
            // Проверяем, что файл существует и не пустой
            if (!Files.exists(sourceFile)) {
                if (LoggerStatusContent.isDebug()) {
                    System.out.println("File does not exist for archiving: " + sourceFile);
                }
                return;
            }

            long fileSize = Files.size(sourceFile);
            if (fileSize == 0) {
                if (LoggerStatusContent.isDebug()) {
                    System.out.println("Skipping archiving of empty file: " + sourceFile);
                }
                return;
            }

            // Используем потокобезопасный форматтер (переиспользуем константу)
            // Добавлены миллисекунды для избежания конфликтов имен при быстрой ротации
            String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMATTER);
            String archiveName = String.format("data-%s-%s.data.gz", timestamp, serviceName);
            Path archivePath = Paths.get(toSendPath, archiveName);

            // ИСПРАВЛЕНО: Сжимаем файл в gzip с переиспользованием буфера
            // Используем ThreadLocal буфер вместо создания нового при каждом вызове
            // Это предотвращает утечку памяти при частом архивировании
            try (InputStream in = Files.newInputStream(sourceFile);
                 BufferedOutputStream bufferedOut = new BufferedOutputStream(
                     Files.newOutputStream(archivePath), BUFFER_SIZE);
                 GZIPOutputStream gzipOut = new GZIPOutputStream(bufferedOut)) {

                // ИСПРАВЛЕНО: Переиспользуем ThreadLocal буфер вместо создания нового
                // Это критично для предотвращения утечки памяти при частом архивировании
                byte[] buffer = ARCHIVE_BUFFER.get();
                int len;
                // ВАЖНО: читаем и записываем только реально прочитанные байты (len)
                // Это гарантирует, что мы не записываем нулевые байты из неиспользованной части буфера
                while ((len = in.read(buffer)) != -1) {
                    if (len > 0) {
                        // Записываем только прочитанные байты, не весь буфер
                        gzipOut.write(buffer, 0, len);
                    }
                }
                // Явный flush для гарантии записи всех данных
                gzipOut.finish();
                // Финальный flush буфера для гарантии записи на диск
                bufferedOut.flush();
            }

            // Проверяем, что архив был создан и не пустой
            // ВАЖНО: Потоки уже закрыты в try-with-resources, данные должны быть на диске
            if (Files.exists(archivePath)) {
                long archiveSize = Files.size(archivePath);
                if (archiveSize > 0) {
                    if (LoggerStatusContent.isDebug()) {
                        System.out.println("Archived monitoring file: " + archiveName +
                            " (source: " + fileSize + " bytes, archive: " + archiveSize + " bytes, path: " + archivePath + ")");
                    }
                } else {
                    if (LoggerStatusContent.isErrorsOrDebug()) {
                        System.err.println("Warning: Created empty archive file: " + archiveName);
                    }
                    // Удаляем пустой архив
                    Files.deleteIfExists(archivePath);
                }
            } else {
                if (LoggerStatusContent.isErrorsOrDebug()) {
                    System.err.println("Error: Archive file was not created: " + archiveName + " in directory: " + toSendPath);
                }
            }

        } catch (IOException e) {
            if (LoggerStatusContent.isErrorsOrDebug()) {
                System.err.println("Error archiving file " + sourceFile + ": " + e.getMessage());
                e.printStackTrace();
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

