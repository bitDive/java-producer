package io.bitdive.jvm_metrics;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Getter
@Setter
public class MetricDto {
    private String name;
    private Map<String, String> tags = new HashMap<>();
    private List<MeasurementDto> measurements = new ArrayList<>();
}
