package io.bitdive.parent.message_producer;

import io.bitdive.parent.parserConfig.YamlParserConfig;
import io.bitdive.parent.trasirovka.agent.utils.LoggerStatusContent;
import io.bitdive.parent.trasirovka.agent.utils.MessageTypeEnum;
import org.apache.logging.log4j.Logger;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

import static io.bitdive.parent.trasirovka.agent.utils.JsonSerializer.SENSITIVE_KEYWORDS;

public class MessageService {
    private static final Logger logger = LibraryLoggerConfig.getLogger(MessageService.class);

    private static final String SPLITTER = "~-~";


    private static void sendMessage(String message) {
        if (message.indexOf('\n') >= 0 || message.indexOf('\r') >= 0) {
            message = message.replace("\n", "").replace("\r", "");
        }
        if (LoggerStatusContent.isDebug()) {
            System.out.println(message);
        }
        logger.info(message);
    }

    private static String buildMessage(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            String s = parts[i];
            if (s == null || "null".equals(s)) {
                s = "";
            }
            sb.append(s);
            if (i < parts.length - 1) {
                sb.append(SPLITTER);
            }
        }
        return sb.toString();
    }

    public static String sanitizeUrl(String url) {
        try {
            URI uri = new URI(url);
            String query = uri.getRawQuery();

            if (query == null || query.isEmpty()) {
                return url;
            }

            StringBuilder newQuery = new StringBuilder();
            String[] pairs = query.split("&");

            for (int i = 0; i < pairs.length; i++) {
                String pair = pairs[i];
                int idx = pair.indexOf("=");
                if (idx > 0) {
                    String key = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8.name());
                    String value = pair.substring(idx + 1);

                    if (SENSITIVE_KEYWORDS.contains(key)) {
                        value = URLEncoder.encode("*****", StandardCharsets.UTF_8.name());
                    }

                    newQuery.append(URLEncoder.encode(key, StandardCharsets.UTF_8.name()))
                            .append("=")
                            .append(value);
                } else {
                    newQuery.append(pair);
                }
                if (i < pairs.length - 1) {
                    newQuery.append("&");
                }
            }

            URI sanitizedUri = new URI(
                    uri.getScheme(),
                    uri.getAuthority(),
                    uri.getPath(),
                    newQuery.toString(),
                    uri.getFragment()
            );

            return sanitizedUri.toString();
        } catch (Exception e) {
            return url;
        }
    }

    public static void sendMessageStart(String messageId,
                                        String className, String methodName, String traceId, String spanId,
                                        OffsetDateTime dateStart, String parentMessage, boolean inPointFlag,
                                        String args, String operationType, String urlRequest, String serviceCallId) {
        sendMessage(buildMessage(
                MessageTypeEnum.STAR.name(),
                YamlParserConfig.getProfilingConfig().getApplication().getModuleName(),
                YamlParserConfig.getProfilingConfig().getApplication().getServiceName(),
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
                urlRequest != null ? sanitizeUrl(urlRequest) : "",
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
                                             String URL, String method, String headers, String body,
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
                sanitizeUrl(URL),
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

    public static void sendMessageCriticalDBError(String url, String ErrorText) {
        sendMessage(buildMessage(
                MessageTypeEnum.CRITICAL_DB_ERROR.name(),
                YamlParserConfig.getProfilingConfig().getApplication().getModuleName(),
                YamlParserConfig.getProfilingConfig().getApplication().getServiceName(),
                sanitizeUrl(url),
                ErrorText,
                OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
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