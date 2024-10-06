package io.bitdive;


import io.bitdive.trasirovka.java_agent.byte_buddy_agent.ByteBuddyAgentRequestWeb;
import io.bitdive.trasirovka.java_agent.byte_buddy_agent.ByteBuddyAgentResponseWeb;
import io.bitdive.parent.trasirovka.agent.byte_buddy_agent.ByteBuddyAgentThread;
import io.bitdive.parent.trasirovka.agent.byte_buddy_agent.ByteBuddyAgentThreadCreator;
import io.bitdive.parent.parserConfig.YamlParserConfig;
import io.bitdive.trasirovka.java_agent.byte_buddy_agent.ByteBuddyAgentBasic;
import io.bitdive.parent.trasirovka.agent.utils.LoggerStatusContent;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

public class ByteBuddyAgentInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    private static boolean initializeAgent = false;
    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        if (initializeAgent) {
            return;
        }
        YamlParserConfig.loadConfig();
        if (LoggerStatusContent.isDebug()) {
            System.out.println("ByteBuddyAgentInitializer initialize start");
        }
        try {
            ByteBuddyAgent.install();
            ByteBuddyAgentBasic.init();
            ByteBuddyAgentThread.init();
            ByteBuddyAgentRequestWeb.init();
            ByteBuddyAgentResponseWeb.init();
            ByteBuddyAgentThreadCreator.init();

            initializeAgent = true;
        } catch (Exception e) {
            if (LoggerStatusContent.isErrorsOrDebug()) {
                System.out.println("ByteBuddyAgentInitializer initialize " + e.getMessage());
            }
        }
    }
}