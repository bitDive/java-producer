package io.bitdive.parent.parserConfig;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class ConfigForServiceDTO {
    private String moduleName;
    private String serviceName;
    private List<String> packedScanner;
    private String serverUrl;
    private String token;
}
