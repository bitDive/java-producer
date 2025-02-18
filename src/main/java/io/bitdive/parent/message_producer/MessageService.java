package io.bitdive.parent.message_producer;

import io.bitdive.parent.parserConfig.YamlParserConfig;
import io.bitdive.parent.trasirovka.agent.utils.LoggerStatusContent;
import io.bitdive.parent.trasirovka.agent.utils.MessageTypeEnum;
import org.apache.logging.log4j.Logger;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;

public class MessageService {
    private static final Logger logger = LibraryLoggerConfig.getLogger(MessageService.class);

    private static final String SPLITTER = "~-~";


    private static void sendMessage(String message) {
        byte[] messageByte = message.replace("\n", "")
                .replace("\r", "")
                .getBytes(StandardCharsets.UTF_8);
        String messageUtf8 = new String(messageByte, StandardCharsets.UTF_8);
        if (LoggerStatusContent.isDebug()) System.out.println(messageUtf8);
        logger.info(messageUtf8);
    }

    private static String buildMessage(String... parts) {
        return Arrays.stream(parts)
                .map(s -> {
                    if (s == null)
                        return "";
                    else
                        return s.equals("null") ? "" : s;
                })
                .collect(Collectors.joining(SPLITTER));
    }

    public static void sendMessageStart(String moduleName, String serviceName, String messageId,
                                        String className, String methodName, String traceId, String spanId,
                                        OffsetDateTime dateStart, String parentMessage, boolean inPointFlag,
                                        String args, String operationType, String urlRequest, String serviceCallId) {
        sendMessage(buildMessage(
                MessageTypeEnum.STAR.name(),
                moduleName,
                serviceName,
                messageId,
                className,
                methodName,
                traceId,
                spanId,
                dateStart.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                parentMessage,
                Boolean.toString(inPointFlag),
                args,
                operationType,
                urlRequest != null ? urlRequest : "",
                YamlParserConfig.getLibraryVersion(),
                YamlParserConfig.getUUIDService(),
                serviceCallId
        ));
    }

    public static void sendMessageEnd(String messageId, OffsetDateTime dateEnd,
                                      String errorCallMessage, String methodReturn, String traceId, String spanId) {
        sendMessage(buildMessage(
                MessageTypeEnum.END.name(),
                messageId,
                dateEnd.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                errorCallMessage,
                methodReturn,
                traceId,
                spanId
        ));
    }

    public static void sendMessageRequestUrl(String messageId, String traceId, String spanId,
                                             OffsetDateTime dateStart, OffsetDateTime dateEnd,
                                             String URI, String method, String headers, String body,
                                             String statusCode, String responseHeaders, String responseBody,
                                             String errorCall, String parentMessageId, String serviceCallId
    ) {
        sendMessage(buildMessage(
                MessageTypeEnum.WEB_REQUEST.name(),
                messageId,
                traceId,
                spanId,
                dateStart.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                dateEnd.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                URI,
                method,
                headers,
                body,
                statusCode,
                responseHeaders,
                responseBody,
                errorCall,
                parentMessageId,
                YamlParserConfig.getLibraryVersion(),
                serviceCallId
        ));
    }

    public static void sendMessageSQLStart(String messageId, String traceId, String spanId,
                                           String sql, String connectionUrl,
                                           OffsetDateTime dateStart, String parentMessageId) {
        sendMessage(buildMessage(
                MessageTypeEnum.SQL_START.name(),
                messageId,
                traceId,
                spanId,
                sql,
                connectionUrl,
                dateStart.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                parentMessageId,
                YamlParserConfig.getLibraryVersion()
        ));
    }

    public static void sendMessageSQLEnd(String messageId, String traceId, String spanId, OffsetDateTime dateEnd, String error) {
        sendMessage(buildMessage(
                MessageTypeEnum.SQL_END.name(),
                messageId,
                traceId,
                spanId,
                dateEnd.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                Optional.ofNullable(error).orElse("")
        ));
    }

    public static void sendMessageWebResponse(String messageId, String traceId, String spanId, Integer codeResponse) {
        String codeResponseStr = codeResponse != null ? codeResponse.toString() : "";
        sendMessage(buildMessage(
                MessageTypeEnum.WEB_RESPONSE.name(),
                messageId,
                traceId,
                spanId,
                codeResponseStr,
                YamlParserConfig.getLibraryVersion()
        ));
    }

}