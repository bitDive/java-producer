package io.bitdive;

import io.bitdive.parent.parserConfig.YamlParserConfig;
import io.bitdive.parent.trasirovka.agent.utils.LoggerStatusContent;
import io.bitdive.shaded.org.apache.commons.lang3.ObjectUtils;
import org.springframework.boot.BootstrapRegistry;
import org.springframework.boot.BootstrapRegistryInitializer;
import org.springframework.core.env.AbstractEnvironment;

public class BitDiveBootstrapRegistryInitializer implements BootstrapRegistryInitializer {

    @Override
    public void initialize(BootstrapRegistry registry) {
        try {
            String activeProfiles = System.getProperty(AbstractEnvironment.ACTIVE_PROFILES_PROPERTY_NAME);
            if (ObjectUtils.isEmpty(activeProfiles)) {
                activeProfiles = System.getenv("SPRING_PROFILES_ACTIVE");
            }
            String[] profiles = new String[]{};
            if (ObjectUtils.isNotEmpty(activeProfiles)) {
                profiles = activeProfiles.split(",");
            }

            ByteBuddyAgentInitializer.initBitDive(profiles);
        } catch (Exception e) {
            if (LoggerStatusContent.isErrorsOrDebug()) {
                YamlParserConfig.setWork(false);
                System.out.println("ByteBuddyAgentInitializer initialize error " + e.getMessage());
            }
        }
    }
}
