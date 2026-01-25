package io.bitdive.parent.trasirovka.agent.byte_buddy_agent.db;

import com.github.f4b6a3.uuid.UuidCreator;
import io.bitdive.parent.trasirovka.agent.byte_buddy_agent.db.cached.ByteBuddyCachedOpenSearchReqest;
import io.bitdive.parent.trasirovka.agent.byte_buddy_agent.db.cached.ByteBuddyCachedOpenSearchResponse;
import io.bitdive.parent.trasirovka.agent.utils.*;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.implementation.FieldAccessor;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.implementation.bind.annotation.*;
import net.bytebuddy.matcher.ElementMatchers;

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

    public interface BitDiveOpenSearchRestClientView {
        Object bitdiveNodeTuple();
        String bitdiveFirstHostString();
    }

    public interface BitDiveOpenSearchNodeTupleView {
        List<?> bitdiveNodes();
    }

    public interface BitDiveOpenSearchNodeView {
        Object bitdiveHost();
    }

    public interface BitDiveHttpHostView {
        String bitdiveSchemeName();
        String bitdiveHostName();
        int bitdivePort();
    }

    /*==================================================================*/
    /*  init                                                            */
    /*==================================================================*/
    public static AgentBuilder init(AgentBuilder agentBuilder)  {
        return agentBuilder

                // RestClient host view (avoid reflection on private fields)
                .type(ElementMatchers.named("org.opensearch.client.RestClient"))
                .transform((b, td, cl, m, sd) -> b
                        .implement(BitDiveOpenSearchRestClientView.class)
                        .defineMethod("bitdiveNodeTuple", Object.class, Visibility.PUBLIC)
                        .intercept(FieldAccessor.ofField("nodeTuple"))
                        .defineMethod("bitdiveFirstHostString", String.class, Visibility.PUBLIC)
                        .intercept(MethodDelegation.to(RestClientHostHelper.class)))

                // NodeTuple view: expose nodes field without reflection
                .type(ElementMatchers.nameStartsWith("org.opensearch.client")
                        .and(ElementMatchers.nameContains("NodeTuple"))
                        .and(ElementMatchers.declaresField(ElementMatchers.named("nodes"))))
                .transform((b, td, cl, m, sd) -> b
                        .implement(BitDiveOpenSearchNodeTupleView.class)
                        .defineMethod("bitdiveNodes", List.class, Visibility.PUBLIC)
                        .intercept(FieldAccessor.ofField("nodes")))

                // Node view: expose getHost()
                .type(ElementMatchers.nameStartsWith("org.opensearch.client")
                        .and(ElementMatchers.nameEndsWith("Node")))
                .transform((b, td, cl, m, sd) -> {
                    try {
                        return b.implement(BitDiveOpenSearchNodeView.class)
                                .defineMethod("bitdiveHost", Object.class, Visibility.PUBLIC)
                                .intercept(net.bytebuddy.implementation.MethodCall.invoke(
                                        td.getDeclaredMethods().filter(ElementMatchers.named("getHost").and(ElementMatchers.takesArguments(0))).getOnly()
                                ));
                    } catch (Exception e) {
                        return b;
                    }
                })

                // HttpHost view (HC4/HC5): expose scheme/host/port
                .type(ElementMatchers.nameEndsWith("HttpHost").and(ElementMatchers.nameStartsWith("org.apache")))
                .transform((b, td, cl, m, sd) -> {
                    try {
                        return b.implement(BitDiveHttpHostView.class)
                                .defineMethod("bitdiveSchemeName", String.class, Visibility.PUBLIC)
                                .intercept(net.bytebuddy.implementation.MethodCall.invoke(
                                        td.getDeclaredMethods().filter(ElementMatchers.named("getSchemeName").and(ElementMatchers.takesArguments(0))).getOnly()
                                ))
                                .defineMethod("bitdiveHostName", String.class, Visibility.PUBLIC)
                                .intercept(net.bytebuddy.implementation.MethodCall.invoke(
                                        td.getDeclaredMethods().filter(ElementMatchers.named("getHostName").and(ElementMatchers.takesArguments(0))).getOnly()
                                ))
                                .defineMethod("bitdivePort", int.class, Visibility.PUBLIC)
                                .intercept(net.bytebuddy.implementation.MethodCall.invoke(
                                        td.getDeclaredMethods().filter(ElementMatchers.named("getPort").and(ElementMatchers.takesArguments(0))).getOnly()
                                ));
                    } catch (Exception e) {
                        return b;
                    }
                })

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
            Object requestHeaders = null;
            if (request instanceof ByteBuddyCachedOpenSearchReqest.BitDiveOpenSearchRequestView) {
                requestHeaders = ((ByteBuddyCachedOpenSearchReqest.BitDiveOpenSearchRequestView) request).bitdiveOptions();
            }

            // Извлекаем информацию о хосте
            String hostInfo = null;
            if (restClient instanceof BitDiveOpenSearchRestClientView) {
                hostInfo = ((BitDiveOpenSearchRestClientView) restClient).bitdiveFirstHostString();
            }

            /* ---------- запрос URL ---------- */
            String requestUrl = "";
            String httpMethod = "";
            String reqBody = "";
            if (request instanceof ByteBuddyCachedOpenSearchReqest.BitDiveOpenSearchRequestView) {
                ByteBuddyCachedOpenSearchReqest.BitDiveOpenSearchRequestView rq =
                        (ByteBuddyCachedOpenSearchReqest.BitDiveOpenSearchRequestView) request;
                requestUrl = String.valueOf(rq.bitdiveEndpoint());
                httpMethod = String.valueOf(rq.bitdiveMethod());
                requestUrl = (hostInfo != null ? hostInfo : "") + requestUrl;
                // force cache population
                try {
                    rq.bitdiveGetEntity();
                } catch (Exception ignored) {
                }
                reqBody = toSafeBodyString(rq.bitdiveCachedBody());
            }


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
                if (resp instanceof ByteBuddyCachedOpenSearchResponse.BitDiveOpenSearchResponseView) {
                    ByteBuddyCachedOpenSearchResponse.BitDiveOpenSearchResponseView r =
                            (ByteBuddyCachedOpenSearchResponse.BitDiveOpenSearchResponseView) resp;
                    status = String.valueOf(r.bitdiveStatusCodeValue());
                    respHeaders = r.bitdiveHeaders();
                    try {
                        r.bitdiveGetEntity(); // fills cache
                    } catch (Exception ignored) {
                    }
                    respBody = toSafeBodyString(r.bitdiveCachedBody());
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

    public static final class RestClientHostHelper {
        @RuntimeType
        public static String bitdiveFirstHostString(@This Object self) {
            try {
                if (!(self instanceof BitDiveOpenSearchRestClientView)) return null;
                Object nodeTuple = ((BitDiveOpenSearchRestClientView) self).bitdiveNodeTuple();
                if (!(nodeTuple instanceof BitDiveOpenSearchNodeTupleView)) return null;
                List<?> nodes = ((BitDiveOpenSearchNodeTupleView) nodeTuple).bitdiveNodes();
                if (nodes == null || nodes.isEmpty()) return null;
                Object node = nodes.get(0);
                if (!(node instanceof BitDiveOpenSearchNodeView)) return null;
                Object host = ((BitDiveOpenSearchNodeView) node).bitdiveHost();
                if (!(host instanceof BitDiveHttpHostView)) return null;
                BitDiveHttpHostView h = (BitDiveHttpHostView) host;
                String scheme = h.bitdiveSchemeName();
                String hostname = h.bitdiveHostName();
                int port = h.bitdivePort();
                if (scheme == null) scheme = "http";
                if (hostname == null) return null;
                if (port <= 0) return scheme + "://" + hostname;
                return scheme + "://" + hostname + ":" + port;
            } catch (Exception e) {
                return null;
            }
        }
    }
}
