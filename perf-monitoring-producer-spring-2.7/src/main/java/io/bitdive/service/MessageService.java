package io.bitdive.service;

import io.bitdive.message_producer.LibraryLoggerConfig;
import io.bitdive.parent.trasirovka.agent.utils.LoggerStatusContent;
import org.apache.logging.log4j.Logger;
public class MessageService {
    private static final Logger logger = LibraryLoggerConfig.getLogger(MessageService.class);
    public static void sendMessage( String messageKafka ) {
        if (LoggerStatusContent.isDebug()) System.out.println(messageKafka);
        logger.info(messageKafka);
    }

}