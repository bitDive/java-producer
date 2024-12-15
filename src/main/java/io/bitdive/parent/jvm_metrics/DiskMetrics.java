package io.bitdive.parent.jvm_metrics;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DiskMetrics {
    private String name;
    private String type;
    private long totalSpace;    // Общий объем диска в байтах
    private long usableSpace;   // Доступное пространство для пользователя в байтах
    private long freeSpace;     // Свободное пространство на диске в байтах

    @Override
    public String toString() {
        return "{"
                + "\"name\": \"" + JsonUtil.escapeJson(name) + "\", "
                + "\"type\": \"" + JsonUtil.escapeJson(type) + "\", "
                + "\"totalSpace\": " + totalSpace + ", "
                + "\"usableSpace\": " + usableSpace + ", "
                + "\"freeSpace\": " + freeSpace
                + "}";
    }
}
