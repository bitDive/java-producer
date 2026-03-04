package io.bitdive.parent.message_producer;


public class MonitoringLogger {
    
    private final MonitoringFileWriter fileWriter;
    
    public MonitoringLogger(MonitoringFileWriter fileWriter) {
        this.fileWriter = fileWriter;
    }
    

    public void info(String message) {
        if (message != null) {
            fileWriter.write(message);
        }
    }
    

    public void info(Object message) {
        if (message != null) {
            fileWriter.write(message.toString());
        }
    }
    

    public void info(String format, Object... args) {
        if (format != null) {
            String message = String.format(format, args);
            fileWriter.write(message);
        }
    }

    public void error(String message) {
        if (message != null) {
            fileWriter.write("ERROR: " + message);
        }
    }
    

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
    

    public void warn(String message) {
        if (message != null) {
            fileWriter.write("WARN: " + message);
        }
    }
    

    public void debug(String message) {
        if (message != null) {
            fileWriter.write("DEBUG: " + message);
        }
    }
}

