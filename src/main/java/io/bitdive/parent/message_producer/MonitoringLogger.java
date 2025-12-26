package io.bitdive.parent.message_producer;

/**
 * Легковесный logger для мониторинговых данных.
 * Заменяет org.apache.logging.log4j.Logger для избежания конфликтов с клиентским приложением.
 */
public class MonitoringLogger {
    
    private final MonitoringFileWriter fileWriter;
    
    public MonitoringLogger(MonitoringFileWriter fileWriter) {
        this.fileWriter = fileWriter;
    }
    
    /**
     * Запись информационного сообщения (асинхронно)
     */
    public void info(String message) {
        if (message != null) {
            fileWriter.write(message);
        }
    }
    
    /**
     * Запись информационного сообщения с объектом (асинхронно)
     */
    public void info(Object message) {
        if (message != null) {
            fileWriter.write(message.toString());
        }
    }
    
    /**
     * Запись с форматированием
     */
    public void info(String format, Object... args) {
        if (format != null) {
            String message = String.format(format, args);
            fileWriter.write(message);
        }
    }
    
    /**
     * Запись ошибки (для совместимости, если потребуется)
     */
    public void error(String message) {
        if (message != null) {
            fileWriter.write("ERROR: " + message);
        }
    }
    
    /**
     * Запись ошибки с exception
     */
    public void error(String message, Throwable t) {
        if (message != null) {
            StringBuilder sb = new StringBuilder("ERROR: ");
            sb.append(message);
            if (t != null) {
                sb.append(" - ").append(t.getClass().getName()).append(": ").append(t.getMessage());
            }
            fileWriter.write(sb.toString());
        }
    }
    
    /**
     * Запись предупреждения (для совместимости, если потребуется)
     */
    public void warn(String message) {
        if (message != null) {
            fileWriter.write("WARN: " + message);
        }
    }
    
    /**
     * Debug сообщения (для совместимости, если потребуется)
     */
    public void debug(String message) {
        if (message != null) {
            fileWriter.write("DEBUG: " + message);
        }
    }
}

