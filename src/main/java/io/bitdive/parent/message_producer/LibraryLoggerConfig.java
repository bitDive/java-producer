package io.bitdive.parent.message_producer;

import io.bitdive.parent.trasirovka.agent.utils.LoggerStatusContent;


public class LibraryLoggerConfig {

    private static MonitoringFileWriter fileWriter;
    private static FileUploadService uploadService;
    private static MonitoringLogger logger;


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


    public static MonitoringLogger getLogger(Class<?> clazz) {
        if (logger == null) {
            throw new IllegalStateException("LibraryLoggerConfig not initialized. Call init() first.");
        }
        return logger;
    }

    public static void stopLoggerContext() {
        cleanup();
        
        if (LoggerStatusContent.isDebug()) {
            System.out.println("LibraryLoggerConfig stopped");
        }
    }
}

