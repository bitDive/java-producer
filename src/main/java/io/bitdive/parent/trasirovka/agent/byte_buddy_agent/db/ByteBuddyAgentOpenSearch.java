package io.bitdive.parent.trasirovka.agent.byte_buddy_agent.db;

import com.github.f4b6a3.uuid.UuidCreator;
import io.bitdive.parent.parserConfig.YamlParserConfig;
import io.bitdive.parent.trasirovka.agent.utils.*;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.implementation.bind.annotation.*;
import net.bytebuddy.matcher.ElementMatchers;

import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static io.bitdive.parent.message_producer.MessageService.sendMessageRequestUrl;

@SuppressWarnings("unused")
public final class ByteBuddyAgentOpenSearch {

    /*==================================================================*/
    /*  init                                                            */
    /*==================================================================*/
    public static AgentBuilder init(AgentBuilder agentBuilder)  {
        return agentBuilder

                .type(ElementMatchers.named("org.opensearch.client.RestClient"))
                .transform((b, td, cl, m, sd) ->
                        b.method(ElementMatchers.named("performRequest").and(ElementMatchers.takesArguments(1)))
                                .intercept(MethodDelegation.to(PerformSync.class)))
                ;
    }

    /*==================================================================*/
    /*  Sync                                                            */
    /*==================================================================*/
    public static class PerformSync {

        @RuntimeType
        public static Object intercept(@SuperCall Callable<Object> zuper,
                                       @AllArguments Object[] args,
                                       @This Object restClient) throws Throwable {
            if (LoggerStatusContent.getEnabledProfile()) return zuper.call();

            if (ContextManager.getMessageIdQueueNew().isEmpty()) return zuper.call();

            Object request = args[0];                               // Request
            Object requestHeaders = extractHeaders(request);

            // Извлекаем информацию о хосте
            String hostInfo = extractHost(restClient);

            /* ---------- запрос URL ---------- */
            String requestUrl = "";
            String httpMethod = "";
            try {
                Method getEndpointMethod = request.getClass().getMethod("getEndpoint");
                Object endpoint = getEndpointMethod.invoke(request);
                requestUrl = endpoint.toString();

                Method getMethodMethod = request.getClass().getMethod("getMethod");
                httpMethod = (String) getMethodMethod.invoke(request);

                // Формируем полный URL с методом, хостом и параметрами
                requestUrl = (hostInfo != null ? hostInfo : "") + requestUrl;
            } catch (Exception e) {
                if (LoggerStatusContent.isErrorsOrDebug()) {
                    System.err.println("Error getting request URL: " + e.getMessage());
                }
            }

            /* Сначала вызываем getEntity() чтобы заполнить кэш */
            Method getEntityMethodRequest = request.getClass().getMethod("getEntity");
            getEntityMethodRequest.setAccessible(true);
            getEntityMethodRequest.invoke(request);

            Field cachedBodyFieldRequest = request.getClass().getDeclaredField("cachedBody");
            cachedBodyFieldRequest.setAccessible(true);
            byte[] responseBodyBytesRequest = (byte[]) cachedBodyFieldRequest.get(request);

            String reqBody = toSafeBodyString(responseBodyBytesRequest);


            OffsetDateTime start = OffsetDateTime.now();
            Object resp = null;
            Throwable err = null;
            try {
                resp = zuper.call();
                return resp;
            } catch (Throwable t) {
                err = t;
                throw t;
            } finally {
                OffsetDateTime end = OffsetDateTime.now();

                String status = null, respBody = "";
                Object respHeaders = null;
                if (resp != null) {
                    Object sl = resp.getClass().getMethod("getStatusLine").invoke(resp);
                    status = String.valueOf(sl.getClass().getMethod("getStatusCode").invoke(sl));

                    /* Сначала вызываем getEntity() чтобы заполнить кэш */
                    Method getEntityMethod = resp.getClass().getMethod("getEntity");
                    getEntityMethod.setAccessible(true);
                    getEntityMethod.invoke(resp); // Этот вызов заполнит cachedBody через ByteBuddyCachedOpenSearchResponse

                    /* Теперь читаем из кэша */
                    try {
                        Field cachedBodyField = resp.getClass().getDeclaredField("cachedBody");
                        cachedBodyField.setAccessible(true);
                        byte[] responseBodyBytes = (byte[]) cachedBodyField.get(resp);

                        respBody = toSafeBodyString(responseBodyBytes);
                    } catch (NoSuchFieldException e) {
                        /* Если поле cachedBody не найдено, значит ByteBuddyCachedOpenSearchResponse не инструментировал этот класс */
                        if (LoggerStatusContent.isErrorsOrDebug()) {
                            System.err.println("cachedBody field not found: " + e.getMessage());
                        }
                    }

                    respHeaders = extractHeaders(resp);             // ★ новый способ
                }
                String errorCallMessage = "";
                if (err != null)
                    errorCallMessage = ReflectionUtils.objectToString(err);

                sendMessageRequestUrl(
                        UuidCreator.getTimeBased().toString(),
                        ContextManager.getTraceId(),
                        ContextManager.getSpanId(),
                        start, end,
                        requestUrl,
                        httpMethod,
                        ReflectionUtils.objectToString(requestHeaders),
                        reqBody,
                        status,
                        ReflectionUtils.objectToString(respHeaders),
                        respBody,
                        errorCallMessage,
                        ContextManager.getMessageIdQueueNew(),
                        UuidCreator.getTimeBased().toString());
            }
        }
    }


    /*==================================================================*/
    /*  Helpers                                                         */
    /*==================================================================*/
    /**
     * Безопасное преобразование тела в строку:
     * - null → ""
     * - бинарные данные → краткое описание "[binary content, size=... bytes]"
     * - очень большие данные → первые N символов + пометка, что обрезано
     */
    private static String toSafeBodyString(byte[] body) {
        if (body == null) {
            return "";
        }

        final int maxPreviewBytes = 4096; // можно вынести в конфиг при необходимости

        // Проверяем, похоже ли содержимое на бинарное
        if (isLikelyBinary(body)) {
            return "[binary content, size=" + body.length + " bytes]";
        }

        int len = Math.min(body.length, maxPreviewBytes);
        String text = new String(body, 0, len, Charset.defaultCharset());

        if (body.length > maxPreviewBytes) {
            return text + "... [truncated, totalBytes=" + body.length + "]";
        }

        return text;
    }

    /**
     * Примитивная эвристика: много управляющих символов → бинарный контент.
     */
    private static boolean isLikelyBinary(byte[] body) {
        int sample = Math.min(body.length, 512);
        int controlChars = 0;
        for (int i = 0; i < sample; i++) {
            int b = body[i] & 0xFF;
            // Разрешаем стандартные управляющие: таб, перевод строки, возврат каретки
            if (b < 0x09 || (b > 0x0D && b < 0x20)) {
                controlChars++;
            }
        }
        // Если больше 10% байт выглядят как "жёсткие" управляющие — считаем бинарным
        return controlChars > sample / 10;
    }

    private static String extractHost(Object restClient) {
        try {
            // Получаем nodeTuple из RestClient
            Field nodeTupleField = restClient.getClass().getDeclaredField("nodeTuple");
            nodeTupleField.setAccessible(true);
            Object nodeTuple = nodeTupleField.get(restClient);

            if (nodeTuple != null) {
                // Получаем nodes из NodeTuple
                Field nodesField = nodeTuple.getClass().getDeclaredField("nodes");
                nodesField.setAccessible(true);
                Object nodes = nodesField.get(nodeTuple);

                if (nodes != null && nodes instanceof List && !((List<?>) nodes).isEmpty()) {
                    // Получаем первый узел (Node)
                    Object node = ((List<?>) nodes).get(0);

                    // Получаем HttpHost из Node
                    Method getHostMethod = node.getClass().getMethod("getHost");
                    Object host = getHostMethod.invoke(node);

                    if (host != null) {
                        // Получаем схему, хост и порт из HttpHost
                        Method getSchemeNameMethod = host.getClass().getMethod("getSchemeName");
                        String scheme = (String) getSchemeNameMethod.invoke(host);

                        Method getHostNameMethod = host.getClass().getMethod("getHostName");
                        String hostname = (String) getHostNameMethod.invoke(host);

                        Method getPortMethod = host.getClass().getMethod("getPort");
                        int port = (int) getPortMethod.invoke(host);

                        return scheme + "://" + hostname + ":" + port;
                    }
                }
            }
        } catch (Exception e) {
            if (LoggerStatusContent.isErrorsOrDebug()) {
                System.err.println("Error extracting host info: " + e.getMessage());
            }
        }
        return null;
    }

    private static void addTraceHeaders(Object request) {
        try {
            Object opts = request.getClass().getMethod("getOptions").invoke(request);
            Object bldr = opts.getClass().getMethod("toBuilder").invoke(opts);

            Method addH = bldr.getClass().getMethod("addHeader", String.class, String.class);
            addH.invoke(bldr, "x-BitDiv-custom-span-id", ContextManager.getSpanId());
            addH.invoke(bldr, "x-BitDiv-custom-parent-message-id", ContextManager.getMessageIdQueueNew());

            Object newOpts = bldr.getClass().getMethod("build").invoke(bldr);
            request.getClass().getMethod("setOptions", newOpts.getClass()).invoke(request, newOpts);
        } catch (Exception e) {
            if (LoggerStatusContent.isErrorsOrDebug())
                System.err.println("trace headers failed: " + e.getMessage());
        }
    }

    /**
     * Безопасно вызывает любой setEntity(HttpEntity).
     */
    private static void setEntity(Object target, Object entity) {
        try {
            for (Method m : target.getClass().getMethods()) {
                if ("setEntity".equals(m.getName()) && m.getParameterCount() == 1) {
                    m.invoke(target, entity);
                    return;
                }
            }
        } catch (Exception e) {
            if (LoggerStatusContent.isErrorsOrDebug())
                System.err.println("setEntity failed: " + e.getMessage());
        }
    }

    /**
     * Пытается получить заголовки из Response любым доступным способом.
     */
    private static Object extractHeaders(Object resp) {
        try {
            // OpenSearch Response#getHeaders() → Map<String,List<String>>
            Method m = resp.getClass().getMethod("getHeaders");
            return m.invoke(resp);
        } catch (NoSuchMethodException ignore) {
            try {
                // Apache HttpResponse#getAllHeaders() → Header[]
                Method m2 = resp.getClass().getMethod("getAllHeaders");
                return m2.invoke(resp);
            } catch (Exception e) {
                return null;
            }
        } catch (Exception e) {
            return null;
        }
    }
}
