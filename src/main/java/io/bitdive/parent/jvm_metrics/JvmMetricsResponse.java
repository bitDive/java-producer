package io.bitdive.parent.jvm_metrics;

import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static io.bitdive.parent.jvm_metrics.JsonUtil.escapeJson;

@Setter
@Getter
public class JvmMetricsResponse {
    private long heapMemoryUsed;
    private long nonHeapMemoryUsed;
    private int threadCount;
    private long daemonThreadCount;
    private long totalMemory;
    private long freeMemory;
    private long maxMemory;
    private int availableProcessors;

    private double systemCpuLoadPercentage;
    private double processCpuLoadPercentage;
    private long totalSwapSpace;
    private long freeSwapSpace;

    private List<DiskMetrics> diskMetrics;

    private String moduleName;
    private String serviceName;
    private OffsetDateTime createdMetric;

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("{");

        sb.append("\"heapMemoryUsed\": ").append(heapMemoryUsed).append(", ");
        sb.append("\"nonHeapMemoryUsed\": ").append(nonHeapMemoryUsed).append(", ");
        sb.append("\"threadCount\": ").append(threadCount).append(", ");
        sb.append("\"daemonThreadCount\": ").append(daemonThreadCount).append(", ");
        sb.append("\"totalMemory\": ").append(totalMemory).append(", ");
        sb.append("\"freeMemory\": ").append(freeMemory).append(", ");
        sb.append("\"maxMemory\": ").append(maxMemory).append(", ");
        sb.append("\"availableProcessors\": ").append(availableProcessors).append(", ");

        sb.append("\"systemCpuLoadPercentage\": ").append(systemCpuLoadPercentage).append(", ");
        sb.append("\"processCpuLoadPercentage\": ").append(processCpuLoadPercentage).append(", ");
        sb.append("\"totalSwapSpace\": ").append(totalSwapSpace).append(", ");
        sb.append("\"freeSwapSpace\": ").append(freeSwapSpace).append(", ");

        // Добавляем кавычки для строковых полей
        sb.append("\"moduleName\": ").append(moduleName != null ? "\"" + escapeJson(moduleName) + "\"" : "null").append(", ");
        sb.append("\"serviceName\": ").append(serviceName != null ? "\"" + escapeJson(serviceName) + "\"" : "null").append(", ");
        sb.append("\"createdMetric\": ").append(createdMetric != null ? "\"" + createdMetric.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) + "\"" : "null").append(", ");

        sb.append("\"diskMetrics\": [");
        if (diskMetrics != null && !diskMetrics.isEmpty()) {
            for (int i = 0; i < diskMetrics.size(); i++) {
                sb.append(diskMetrics.get(i).toString());
                if (i < diskMetrics.size() - 1) {
                    sb.append(", ");
                }
            }
        }
        sb.append("]");

        sb.append("}");
        return sb.toString();
    }
}
