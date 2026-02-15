package io.bitdive.core.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@AllArgsConstructor
@NoArgsConstructor
public class ParamMethod {
    private int parIndex;
    private String paramType;
    private Object val;
}

