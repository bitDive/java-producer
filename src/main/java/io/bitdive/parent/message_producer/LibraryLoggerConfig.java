package io.bitdive.parent.message_producer;

import io.bitdive.parent.trasirovka.agent.utils.LoggerStatusContent;

/**
 * Облегченная конфигурация логирования для библиотеки мониторинга.
 * Полностью независима от Log4j2/Logback - не влияет на логи клиентского приложения.
 * 
 * Использует собственную легковесную систему:
 * - MonitoringFileWriter для асинхронной записи в файлы
 * - FileUploadService для отправки файлов на сервер
 */
public class LibraryLoggerConfig {

    private static MonitoringFileWriter fileWriter;
    private static FileUploadService uploadService;
    private static MonitoringLogger logger;

    /**
     * Инициализация системы мониторинга
     * ИСПРАВЛЕНО: предотвращение утечки памяти при повторной инициализации
     */
    public static void init() {
        // Если уже инициализировано - не делаем ничего
        if (fileWriter != null) {
            return;
        }

        try {
            // Инициализируем файловый writer
            fileWriter = new MonitoringFileWriter();
            
            // Инициализируем сервис загрузки файлов
            uploadService = new FileUploadService();
            
            // Создаем logger wrapper
            logger = new MonitoringLogger(fileWriter);
            
            if (LoggerStatusContent.isDebug()) {
                System.out.println("LibraryLoggerConfig initialized successfully");
            }
            
        } catch (Exception e) {
            // ВАЖНО: При ошибке инициализации очищаем частично созданные объекты
            // для предотвращения утечки памяти
            // ИСПРАВЛЕНО: ловим все Exception, а не только IOException,
            // так как FileUploadService и MonitoringLogger могут выбросить другие исключения
            cleanup();
            if (LoggerStatusContent.isErrorsOrDebug()) {
                System.err.println("Failed to initialize LibraryLoggerConfig: " + e.getMessage());
            }
            throw new RuntimeException("Failed to initialize monitoring logger", e);
        }
    }
    
    /**
     * Внутренний метод для очистки ресурсов
     * ИСПРАВЛЕНО: предотвращение утечки памяти
     */
    private static void cleanup() {
        if (fileWriter != null) {
            try {
                fileWriter.shutdown();
            } catch (Exception e) {
                if (LoggerStatusContent.isErrorsOrDebug()) {
                    System.err.println("Error cleaning up file writer: " + e.getMessage());
                }
            }
            fileWriter = null;
        }
        
        if (uploadService != null) {
            try {
                uploadService.shutdown();
            } catch (Exception e) {
                if (LoggerStatusContent.isErrorsOrDebug()) {
                    System.err.println("Error cleaning up upload service: " + e.getMessage());
                }
            }
            uploadService = null;
        }
        
        logger = null;
    }

    /**
     * Получить logger для класса
     */
    public static MonitoringLogger getLogger(Class<?> clazz) {
        if (logger == null) {
            throw new IllegalStateException("LibraryLoggerConfig not initialized. Call init() first.");
        }
        return logger;
    }

    /**
     * Правильная остановка системы мониторинга
     * ИСПРАВЛЕНО: использование общего метода cleanup для предотвращения дублирования кода
     */
    public static void stopLoggerContext() {
        cleanup();
        
        if (LoggerStatusContent.isDebug()) {
            System.out.println("LibraryLoggerConfig stopped");
        }
    }
}

