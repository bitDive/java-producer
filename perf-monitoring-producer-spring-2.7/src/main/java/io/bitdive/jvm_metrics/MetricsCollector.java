package io.bitdive.jvm_metrics;

import io.micrometer.core.instrument.Measurement;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Setter
@Getter
public class MetricsCollector {

    private String moduleName;
    private String serviceName;
    private OffsetDateTime createdMetric;
    private String serviceNodeUUID;
    private List<MetricDto> listOfMetrics;

    public MetricsCollector(MeterRegistry meterRegistry) {
        listOfMetrics = collectMetrics(meterRegistry);
    }


    private List<MetricDto> collectMetrics(MeterRegistry meterRegistry) {
        List<MetricDto> metricsDtoList = new ArrayList<>();
        for (Meter meter : meterRegistry.getMeters()) {
            MetricDto dto = new MetricDto();

            dto.setName(meter.getId().getName());

            // Собираем теги метрики (area=heap, id=G1 Old Gen и т.д.)
            for (Tag tag : meter.getId().getTags()) {
                dto.getTags().put(tag.getKey(), tag.getValue());
            }

            for (Measurement measurement : meter.measure()) {
                String statistic = measurement.getStatistic().name();
                double value = measurement.getValue();
                dto.getMeasurements().add(new MeasurementDto(statistic, value));
            }

            metricsDtoList.add(dto);
        }
        return metricsDtoList;
    }
}
