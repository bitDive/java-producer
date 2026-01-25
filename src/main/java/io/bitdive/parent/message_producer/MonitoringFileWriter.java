package io.bitdive.parent.message_producer;

import io.bitdive.parent.parserConfig.YamlParserConfig;
import io.bitdive.parent.trasirovka.agent.utils.LoggerStatusContent;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractOutputStreamAppender;
import org.apache.logging.log4j.core.appender.RollingRandomAccessFileAppender;
import org.apache.logging.log4j.core.appender.rolling.RollingFileManager;
import org.apache.logging.log4j.core.config.builder.api.*;
import org.apache.logging.log4j.core.config.builder.impl.BuiltConfiguration;
import org.apache.logging.log4j.core.layout.PatternLayout;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPOutputStream;

/**
 * Асинхронный writer мониторинга:
 * - write() не блокирует (Log4j2 AsyncAppender blocking=false)
 * - RollingRandomAccessFile + gzip rollover
 * - Периодическая принудительная ротация по таймеру (без записи "пустых" строк)
 * - Изоляция от логов приложения достигается shading+relocation Log4j2.
 */
public class MonitoringFileWriter {

    // ==== Твои дефолты/константы ====
    private static final int ASYNC_BUFFER_SIZE = 10_000;             // аналог QUEUE_CAPACITY
    private static final int FILE_BUFFER_SIZE = 16_384;              // аналог BUFFER_SIZE
    private static final long MAX_FILE_SIZE_BYTES = 100L * 1024 * 1024; // 100 MB
    private static final String ACTIVE_FILE_NAME = "monitoringFile.data";

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss-SSS");

    // Чтобы не аллоцировать буфер на каждый startup-архив
    private static final ThreadLocal<byte[]> STARTUP_ARCHIVE_BUFFER =
            ThreadLocal.withInitial(() -> new byte[FILE_BUFFER_SIZE]);

    // ==== Имена log4j2 компонентов (внутренние) ====
    private static final String CTX_NAME = "BitDiveMonitoringCtx";
    private static final String LOG_NAME = "BitDiveMonitoringLogger";
    private static final String ROLLING_APPENDER = "BitDiveRollingRAFile";
    private static final String ASYNC_APPENDER = "BitDiveAsync";

    private final String baseFilePath;
    private final String toSendPath;
    private final String serviceName;

    private final long maxFileSizeBytes;
    private final int rotationIntervalSeconds;

    private final AtomicBoolean running = new AtomicBoolean(true);

    private LoggerContext ctx;
    private Logger logger;
    private ScheduledExecutorService rotationScheduler;

    // reflection: AbstractOutputStreamAppender#getManager() (protected)
    private static final Method GET_MANAGER_METHOD = initGetManagerMethod();

    public MonitoringFileWriter() throws IOException {
        this.baseFilePath = YamlParserConfig.getProfilingConfig().getMonitoring().getDataFile().getPath();
        this.toSendPath = baseFilePath + java.io.File.separator + "toSend";
        this.serviceName = YamlParserConfig.getProfilingConfig().getApplication().getServiceName();

        this.maxFileSizeBytes = MAX_FILE_SIZE_BYTES;
        this.rotationIntervalSeconds = YamlParserConfig.getProfilingConfig()
                .getMonitoring()
                .getDataFile()
                .getTimerConvertForSend();

        Files.createDirectories(Paths.get(baseFilePath));
        Files.createDirectories(Paths.get(toSendPath));

        // 1) Если остался старый active-файл после крэша — архивируем его как раньше
        Path existingActive = Paths.get(baseFilePath, ACTIVE_FILE_NAME);
        if (Files.exists(existingActive) && Files.size(existingActive) > 0) {
            archiveStandalone(existingActive);
            tryDelete(existingActive);
            if (LoggerStatusContent.isDebug()) {
                System.out.println("Archived existing monitoring file from previous session: " + existingActive);
            }
        }

        // 2) Стартуем изолированный Log4j2 контекст с Rolling + Async
        startIsolatedLog4j2();

        // 3) Периодическая принудительная ротация по таймеру (как у тебя было)
        //    Важно: без "пустых лог-строк" — вызываем manager.rollover()
        this.rotationScheduler = Executors.newSingleThreadScheduledExecutor(new DaemonThreadFactory("MonitoringRotation"));
        this.rotationScheduler.scheduleAtFixedRate(
                this::rolloverOnSchedule,
                rotationIntervalSeconds,
                rotationIntervalSeconds,
                TimeUnit.SECONDS
        );
    }

    /**
     * Не блокирующая запись:
     * - sanitization \u0000
     * - дальше AsyncAppender (blocking=false)
     */
    public void write(String message) {
        if (!running.get()) return;

        String sanitized = sanitizeMessage(message);
        if (sanitized == null || sanitized.isEmpty()) return;

        try {
            // ВАЖНО: logger привязан к нашему LoggerContext, не к глобальному LogManager приложения.
            logger.info(sanitized);
        } catch (Throwable t) {
            // Никогда не валим основное приложение из-за логгера агента
            if (LoggerStatusContent.isErrorsOrDebug()) {
                System.err.println("MonitoringFileWriter: write failed: " + t.getMessage());
            }
        }
    }

    /**
     * Корректная остановка.
     */
    public void shutdown() {
        if (!running.compareAndSet(true, false)) return;

        // останавливаем scheduler
        if (rotationScheduler != null) {
            rotationScheduler.shutdown();
            try {
                if (!rotationScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    rotationScheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                rotationScheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        // финальная принудительная ротация, если файл не пуст
        try {
            rolloverNowIfNotEmpty();
        } catch (Throwable ignored) { }

        // стопаем контекст (flush/stop async thread/appender-ы)
        if (ctx != null) {
            try {
                ctx.stop(10, TimeUnit.SECONDS);
            } catch (Throwable t) {
                try { ctx.stop(); } catch (Throwable ignored) { }
            }
        }

        if (LoggerStatusContent.isDebug()) {
            System.out.println("MonitoringFileWriter shutdown complete");
        }
    }

    // =========================================================================================
    // Log4j2 isolated configuration
    // =========================================================================================

    private void startIsolatedLog4j2() {
        ConfigurationBuilder<BuiltConfiguration> b = ConfigurationBuilderFactory.newConfigurationBuilder();

        // Чтобы Log4j2 не шумел в stdout/stderr
        b.setStatusLevel(Level.ERROR);
        b.setConfigurationName("BitDiveMonitoringConfig");

        // properties
        b.addProperty("baseDir", baseFilePath);
        b.addProperty("toSendDir", toSendPath);
        b.addProperty("serviceName", serviceName);

        // Layout: только сообщение + \n (как у тебя NEWLINE_BYTES)
        LayoutComponentBuilder layout = b.newLayout("PatternLayout")
                .addAttribute("pattern", "%m%n")
                .addAttribute("charset", StandardCharsets.UTF_8);

        // RollingRandomAccessFileAppender -> active file
        String activeFile = Paths.get(baseFilePath, ACTIVE_FILE_NAME).toString();

        // Роллы уходят в toSend как gzip
        // %i добавлен для коллизий
        String filePattern = Paths.get(
                toSendPath,
                "data-%d{yyyy-MM-dd-HH-mm-ss-SSS}-${serviceName}-%i.data.gz"
        ).toString();

        ComponentBuilder<?> policies = b.newComponent("Policies")
                .addComponent(b.newComponent("SizeBasedTriggeringPolicy")
                        .addAttribute("size", toLog4jSize(maxFileSizeBytes)));

        // strategy: не ограничиваем количество, потому что отправляющая часть сама чистит toSend
        ComponentBuilder<?> strategy = b.newComponent("DefaultRolloverStrategy")
                .addAttribute("fileIndex", "nomax");

        AppenderComponentBuilder rolling = b.newAppender(ROLLING_APPENDER, "RollingRandomAccessFile")
                .addAttribute("fileName", activeFile)
                .addAttribute("filePattern", filePattern)
                .addAttribute("append", true)
                .addAttribute("immediateFlush", false)
                .addAttribute("bufferSize", FILE_BUFFER_SIZE)
                .add(layout)
                .addComponent(policies)
                .addComponent(strategy);

        b.add(rolling);

        // Async appender (не блокирует, если очередь полная — дроп)
        AppenderComponentBuilder async = b.newAppender(ASYNC_APPENDER, "Async")
                .addAttribute("bufferSize", ASYNC_BUFFER_SIZE)
                .addAttribute("blocking", false)
                .addAttribute("includeLocation", false)
                .addComponent(b.newAppenderRef(ROLLING_APPENDER));

        b.add(async);

        // Logger только для агента
        LoggerComponentBuilder agentLogger = b.newLogger(LOG_NAME, Level.INFO)
                .add(b.newAppenderRef(ASYNC_APPENDER))
                .addAttribute("additivity", false);

        b.add(agentLogger);

        // Root минимальный (чтобы случайно не писать в наши аппендеры)
        b.add(b.newRootLogger(Level.OFF));

        BuiltConfiguration cfg = b.build();

        this.ctx = new LoggerContext(CTX_NAME);
        this.ctx.start(cfg);
        this.logger = this.ctx.getLogger(LOG_NAME);

        if (LoggerStatusContent.isDebug()) {
            System.out.println("MonitoringFileWriter: Log4j2 context started. Active file: " + activeFile);
        }
    }

    private static String toLog4jSize(long bytes) {
        // log4j2 size syntax: "100MB", "10MB", etc.
        // Для твоего дефолта 100MB просто вернем 100MB.
        long mb = bytes / (1024L * 1024L);
        if (mb > 0 && mb * 1024L * 1024L == bytes) {
            return mb + "MB";
        }
        long kb = bytes / 1024L;
        if (kb > 0 && kb * 1024L == bytes) {
            return kb + "KB";
        }
        return bytes + "B";
    }

    // =========================================================================================
    // Scheduled rollover (preserves your "rotate even when idle" behavior)
    // =========================================================================================

    private void rolloverOnSchedule() {
        if (!running.get()) return;
        try {
            rolloverNowIfNotEmpty();
        } catch (Throwable t) {
            if (LoggerStatusContent.isErrorsOrDebug()) {
                System.err.println("MonitoringFileWriter: scheduled rollover failed: " + t.getMessage());
            }
        }
    }

    private void rolloverNowIfNotEmpty() throws Exception {
        RollingFileManager mgr = getRollingManager();
        if (mgr == null) return;

        long size = mgr.getFileSize();
        if (size <= 0) return;

        // как у тебя: flush перед ротацией
        try { mgr.flush(); } catch (Throwable ignored) { }

        // важное: принудительная ротация без записи "пустой строки"
        mgr.rollover();
    }

    private RollingFileManager getRollingManager() throws Exception {
        if (ctx == null) return null;

        Appender a = ctx.getConfiguration().getAppender(ROLLING_APPENDER);
        if (!(a instanceof RollingRandomAccessFileAppender)) return null;

        Object manager = GET_MANAGER_METHOD.invoke(a);
        if (manager instanceof RollingFileManager) {
            return (RollingFileManager) manager;
        }
        return null;
    }

    private static Method initGetManagerMethod() {
        try {
            Method m = AbstractOutputStreamAppender.class.getDeclaredMethod("getManager");
            m.setAccessible(true);
            return m;
        } catch (Exception e) {
            // Если вдруг log4j2 поменяет API — просто не будем делать принудительный rollover
            // (но запись продолжит работать).
            throw new IllegalStateException("Cannot access Log4j2 appender manager (getManager).", e);
        }
    }

    // =========================================================================================
    // Message sanitization (\u0000 filtering)
    // =========================================================================================

    private static String sanitizeMessage(String message) {
        if (message == null) return null;

        int idx = message.indexOf('\0');
        if (idx < 0) return message;

        // удаляем все \u0000
        StringBuilder sb = new StringBuilder(message.length());
        for (int i = 0; i < message.length(); i++) {
            char c = message.charAt(i);
            if (c != 0) sb.append(c);
        }
        return sb.toString();
    }

    // =========================================================================================
    // Startup archiving (preserves your previous-session behavior)
    // =========================================================================================

    private void archiveStandalone(Path sourceFile) {
        try {
            if (!Files.exists(sourceFile)) return;
            long fileSize = Files.size(sourceFile);
            if (fileSize <= 0) return;

            String timestamp = LocalDateTime.now().format(TS);
            String archiveName = String.format("data-%s-%s-0.data.gz", timestamp, serviceName);
            Path archivePath = Paths.get(toSendPath, archiveName);

            try (InputStream in = Files.newInputStream(sourceFile);
                 BufferedOutputStream bos = new BufferedOutputStream(Files.newOutputStream(archivePath), FILE_BUFFER_SIZE);
                 GZIPOutputStream gzip = new GZIPOutputStream(bos)) {

                byte[] buf = STARTUP_ARCHIVE_BUFFER.get();
                int len;
                while ((len = in.read(buf)) != -1) {
                    if (len > 0) gzip.write(buf, 0, len);
                }
                gzip.finish();
                bos.flush();
            }

            if (LoggerStatusContent.isDebug()) {
                long gzSize = Files.exists(archivePath) ? Files.size(archivePath) : -1;
                System.out.println("Startup archive created: " + archivePath + " (src=" + fileSize + ", gz=" + gzSize + ")");
            }
        } catch (Throwable t) {
            if (LoggerStatusContent.isErrorsOrDebug()) {
                System.err.println("MonitoringFileWriter: startup archiving failed: " + t.getMessage());
            }
        }
    }

    private static void tryDelete(Path p) {
        try { Files.deleteIfExists(p); } catch (IOException ignored) { }
    }

    // =========================================================================================
    // Threads
    // =========================================================================================

    private static final class DaemonThreadFactory implements ThreadFactory {
        private final String name;

        private DaemonThreadFactory(String name) {
            this.name = name;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        }
    }
}
