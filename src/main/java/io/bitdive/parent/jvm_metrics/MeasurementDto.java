package io.bitdive.parent.jvm_metrics;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
public class MeasurementDto {
    private String statistic;
    private double value;
}
