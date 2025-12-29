package io.bitdive.parent.dto;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Getter
@Builder
public class ApplicationEnvironment {
    private String moduleName;
    private String serviceName;
    private String serviceNodeUUID;
    private List<String> activeProfiles;
    private Map<String,String> environmentApplication;
}
