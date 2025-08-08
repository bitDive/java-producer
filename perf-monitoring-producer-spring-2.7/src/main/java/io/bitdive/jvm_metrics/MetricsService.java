package io.bitdive.jvm_metrics;


import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonSerializer;
import io.bitdive.parent.parserConfig.YamlParserConfig;
import io.bitdive.parent.trasirovka.agent.utils.LoggerStatusContent;
import io.micrometer.core.instrument.MeterRegistry;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

public class MetricsService {
    private MetricsCollector metricsCollector;
    private static final Gson mapper = new GsonBuilder()
            .registerTypeAdapter(OffsetDateTime.class, (JsonSerializer<OffsetDateTime>) (src, typeOfSrc, context) ->
                    context.serialize(src.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)))
            .registerTypeAdapter(OffsetDateTime.class, (JsonDeserializer<OffsetDateTime>) (json, type, context) ->
                    OffsetDateTime.parse(json.getAsString(), DateTimeFormatter.ISO_OFFSET_DATE_TIME))
            .create();


    public MetricsService(MeterRegistry meterRegistry) {
        try {
            metricsCollector = new MetricsCollector(meterRegistry);
            metricsCollector.setModuleName(YamlParserConfig.getProfilingConfig().getApplication().getModuleName());
            metricsCollector.setServiceName(YamlParserConfig.getProfilingConfig().getApplication().getServiceName());
            metricsCollector.setCreatedMetric(OffsetDateTime.now());
            metricsCollector.setServiceNodeUUID(YamlParserConfig.getUUIDService());
        } catch (Exception e) {
            if (LoggerStatusContent.isErrorsOrDebug())
                System.err.println("Failed to write metrics to file: " + e.getMessage());
        }
    }


    public String sendMetrics() {
        try {
            return mapper.toJson(metricsCollector);
        } catch (Exception e) {
            System.out.println("Error while sending metrics: " + e.getMessage());
        }
        return "";
    }
}
