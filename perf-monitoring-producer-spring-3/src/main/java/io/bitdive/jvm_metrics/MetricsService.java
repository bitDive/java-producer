package io.bitdive.jvm_metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import io.bitdive.parent.parserConfig.YamlParserConfig;
import io.bitdive.parent.trasirovka.agent.utils.LoggerStatusContent;
import io.micrometer.core.instrument.MeterRegistry;

import java.time.OffsetDateTime;

public class MetricsService {

    private MetricsCollector metricsCollector;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public MetricsService(MeterRegistry meterRegistry) {
        MetricsCollector mc = null;
        try {
            mc = new MetricsCollector(meterRegistry);
            mc.setModuleName(YamlParserConfig.getProfilingConfig().getApplication().getModuleName());
            mc.setServiceName(YamlParserConfig.getProfilingConfig().getApplication().getServiceName());
            mc.setCreatedMetric(OffsetDateTime.now());
            mc.setServiceNodeUUID(YamlParserConfig.getUUIDService());
        } catch (Exception e) {
            if (LoggerStatusContent.isErrorsOrDebug()) {
                System.err.println("Failed to init metrics collector: " + e.getMessage());
            }
        }
        this.metricsCollector = mc;
    }

    public String sendMetrics() {
        if (metricsCollector == null) return "";
        try {
            if (YamlParserConfig.getApplicationEnvironment() != null){
                metricsCollector.setApplicationEnvironment(YamlParserConfig.getApplicationEnvironment());
            }
            String dataMetrics= MAPPER.writeValueAsString(metricsCollector);
            metricsCollector = null;
            YamlParserConfig.setApplicationEnvironment(null);
            return dataMetrics;
        } catch (Exception e) {
            System.out.println("Error while sending metrics: " + e.getMessage());
            return "";
        }
    }
}
