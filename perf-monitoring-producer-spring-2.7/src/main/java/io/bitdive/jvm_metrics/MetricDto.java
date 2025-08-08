package io.bitdive.jvm_metrics;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class MetricDto {
    private String name;
    private List<MeasurementDto> measurements = new ArrayList<>();
}
