package io.bitdive.parent.message_producer;

import io.bitdive.parent.trasirovka.agent.utils.LoggerStatusContent;

import java.io.IOException;

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
     */
    public static void init() {
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
            
        } catch (IOException e) {
            if (LoggerStatusContent.isErrorsOrDebug()) {
                System.err.println("Failed to initialize LibraryLoggerConfig: " + e.getMessage());
            }
            throw new RuntimeException("Failed to initialize monitoring logger", e);
        }
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
     */
    public static void stopLoggerContext() {
        if (fileWriter != null) {
            try {
                fileWriter.shutdown();
            } catch (Exception e) {
                if (LoggerStatusContent.isErrorsOrDebug()) {
                    System.err.println("Error stopping file writer: " + e.getMessage());
                }
            }
        }
        
        if (uploadService != null) {
            try {
                uploadService.shutdown();
            } catch (Exception e) {
                if (LoggerStatusContent.isErrorsOrDebug()) {
                    System.err.println("Error stopping upload service: " + e.getMessage());
                }
            }
        }
        
        if (LoggerStatusContent.isDebug()) {
            System.out.println("LibraryLoggerConfig stopped");
        }
        
        fileWriter = null;
        uploadService = null;
        logger = null;
    }
}
