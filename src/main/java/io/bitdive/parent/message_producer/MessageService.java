package io.bitdive.parent.message_producer;

import io.bitdive.parent.trasirovka.agent.utils.LoggerStatusContent;
import io.bitdive.parent.trasirovka.agent.utils.MessageTypeEnum;
import org.apache.logging.log4j.Logger;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class MessageService {
    private static final Logger logger = LibraryLoggerConfig.getLogger(MessageService.class);

    private static final String SPLITTER = "~-~";
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;


    private static void sendMessage(String message) {
        byte[] messageByte = message.replace("\n", "")
                .replace("\r", "")
                .getBytes(StandardCharsets.UTF_8);
        String messageUtf8 = new String(messageByte, StandardCharsets.UTF_8);
        if (LoggerStatusContent.isDebug()) System.out.println(messageUtf8);
        logger.info(messageUtf8);
    }

    private static String buildMessage(String... parts) {
        return String.join(SPLITTER, parts);
    }


    /**
     * Отправляет сообщение типа STAR.
     *
     * @param moduleName    Имя модуля.
     * @param serviceName   Имя сервиса.
     * @param messageId     Идентификатор сообщения.
     * @param className     Имя класса.
     * @param methodName    Имя метода.
     * @param traceId       Trace ID.
     * @param spanId        Span ID.
     * @param dateStart     Дата и время начала.
     * @param parentMessage Родительское сообщение.
     * @param inPointFlag   Флаг точки входа.
     * @param args          Аргументы метода.
     * @param operationType Тип операции.
     * @param urlRequest    URL запроса.
     */
    public static void sendMessageStart(String moduleName, String serviceName, String messageId,
                                        String className, String methodName, String traceId, String spanId,
                                        LocalDateTime dateStart, String parentMessage, boolean inPointFlag,
                                        String args, String operationType, String urlRequest) {
        sendMessage(buildMessage(
                MessageTypeEnum.STAR.name(),
                moduleName,
                serviceName,
                messageId,
                className,
                methodName,
                traceId,
                spanId,
                dateStart.format(DATE_FORMATTER),
                parentMessage,
                Boolean.toString(inPointFlag),
                args,
                operationType,
                urlRequest != null ? urlRequest : ""
        ));
    }

    /**
     * Отправляет сообщение типа END.
     *
     * @param messageId        Идентификатор сообщения.
     * @param dateEnd          Дата и время окончания.
     * @param errorCallMessage Сообщение об ошибке вызова.
     * @param methodReturn     Возвращаемое значение метода.
     * @param traceId          Trace ID.
     */
    public static void sendMessageEnd(String messageId, LocalDateTime dateEnd,
                                      String errorCallMessage, String methodReturn, String traceId) {
        sendMessage(buildMessage(
                MessageTypeEnum.END.name(),
                messageId,
                dateEnd.format(DATE_FORMATTER),
                errorCallMessage,
                methodReturn,
                traceId
        ));
    }

    /**
     * Отправляет сообщение типа SQL.
     *
     * @param messageId Идентификатор сообщения.
     * @param traceId   Trace ID.
     * @param spanId    Span ID.
     * @param sql       SQL запрос.
     */
    public static void sendMessageSQL(String messageId, String traceId, String spanId, String sql) {
        sendMessage(buildMessage(
                MessageTypeEnum.SQL.name(),
                messageId,
                traceId,
                spanId,
                sql
        ));
    }

    /**
     * Отправляет сообщение типа WEB_RESPONSE.
     *
     * @param messageId    Идентификатор сообщения.
     * @param traceId      Trace ID.
     * @param spanId       Span ID.
     * @param codeResponse Код ответа.
     */
    public static void sendMessageWebResponse(String messageId, String traceId, String spanId, Integer codeResponse) {
        String codeResponseStr = codeResponse != null ? codeResponse.toString() : "";
        sendMessage(buildMessage(
                MessageTypeEnum.WEB_RESPONSE.name(),
                messageId,
                traceId,
                spanId,
                codeResponseStr
        ));
    }

}