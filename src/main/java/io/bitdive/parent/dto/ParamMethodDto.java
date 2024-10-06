package io.bitdive.parent.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@AllArgsConstructor
@NoArgsConstructor
public class ParamMethodDto {
    private int parIndex;
    private String paramType;
    private Object val;
}