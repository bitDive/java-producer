package io.bitdive.parent.parserConfig;

import java.util.List;

/**
 * Minimal contract required to request service configuration from BitDive server.
 *
 * <p>Implemented by DTOs like {@link ConfigForServiceDTO}.</p>
 */
public interface ConfigForService {
    String getModuleName();

    String getServiceName();

    List<String> getPackedScanner();

    String getServerUrl();

    String getToken();
}

