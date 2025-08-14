package io.bitdive.parent.parserConfig;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ConfigForServiceDTO {
    private String moduleName;
    private String serviceName;
    private List<String> packedScanner;
    private String serverUrl;
    private String token;
}
