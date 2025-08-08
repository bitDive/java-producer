package io.bitdive.parent.trasirovka.agent.byte_buddy_agent;

import com.github.f4b6a3.uuid.UuidCreator;
import io.bitdive.parent.parserConfig.YamlParserConfig;
import io.bitdive.parent.trasirovka.agent.utils.*;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;

import java.io.ByteArrayOutputStream;
import java.lang.annotation.Annotation;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;

import static io.bitdive.parent.message_producer.MessageService.*;

public class ByteBuddyAgentSoap {


    public static final String[] SAAJ_CONNECTION_CLASS = {
            "jakarta.xml.soap.SOAPConnection",
            "javax.xml.soap.SOAPConnection"
    };

    public static final String[] SOAP_MESSAGE_CLASS = {
            "jakarta.xml.soap.SOAPMessage",
            "javax.xml.soap.SOAPMessage"
    };

    public static ResettableClassFileTransformer init(Instrumentation inst) {

        ElementMatcher.Junction<TypeDescription> saajMatcher = ElementMatchers.none();
        for (String c : SAAJ_CONNECTION_CLASS) {
            saajMatcher = saajMatcher.or(ElementMatchers.hasSuperType(ElementMatchers.named(c)));
        }

        return new AgentBuilder.Default()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)

                /* --- Spring-WS Template --- */
                .type(ElementMatchers.named("org.springframework.ws.client.core.WebServiceTemplate"))
                .transform((b, td, cl, m, dd) ->
                        b.visit(Advice.to(SpringWsAdvice.class)
                                .on(ElementMatchers.named("marshalSendAndReceive")
                                        .and(ElementMatchers.takesArguments(2))
                                )))


                /* --- JAX-WS RI (Metro) SEIStub ------------------------------------ */
                .type(ElementMatchers.named("com.sun.xml.ws.client.sei.SEIStub"))
                .transform((b, td, cl, m, dd) ->
                        b.visit(Advice.to(SeiStubAdvice.class)
                                .on(ElementMatchers.named("invoke"))))

                // --- Apache CXF proxy ---
                .type(ElementMatchers.named("org.apache.cxf.jaxws.JaxWsClientProxy"))
                .transform((b, td, cl, m, dd) ->
                        b.visit(Advice.to(CxfAdvice.class)
                                .on(ElementMatchers.named("invoke"))))

                .installOn(inst);
    }

    /* ------------------------------------------------------------------ */
    /*  Metro (SEIStub.invoke)                                            */
    /* ------------------------------------------------------------------ */
    public static class SeiStubAdvice {

        @Advice.OnMethodEnter
        public static MethodCtx onEnter(@Advice.This Object stub,      // com.sun.xml.ws.client.sei.SEIStub
                                        @Advice.Argument(1) Method m,
                                        @Advice.Argument(2) Object[] args) {

            MethodCtx ctx = new MethodCtx();
            if (LoggerStatusContent.getEnabledProfile()) return ctx;
            ctx.uuidMessage = UuidCreator.getTimeBased().toString();
            ctx.traceId = ContextManager.getTraceId();
            ctx.spanId = ContextManager.getSpanId();

            String operation = m.getName();
            String soapRequest = ReflectionUtils.objectToString(args);
            String endpoint = tryEndpointFromStub(stub);

            if (!ContextManager.isMessageIdQueueEmpty()) {
                sendMessageSOAPStart(
                        ctx.uuidMessage,
                        ctx.traceId,
                        ctx.spanId,
                        endpoint,
                        operation,
                        soapRequest,
                        OffsetDateTime.now(),
                        ContextManager.getMessageIdQueueNew(),
                        MessageTypeEnum.SOAP_START);
            }
            return ctx;
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void onExit(@Advice.Enter MethodCtx ctx,
                                  @Advice.Return Object result,
                                  @Advice.Thrown Throwable t) {
            if (LoggerStatusContent.getEnabledProfile()) return;
            String soapResponse = ReflectionUtils.objectToString(result);

            if (!ContextManager.isMessageIdQueueEmpty()) {
                sendMessageSOAPEnd(
                        ctx.uuidMessage,
                        ctx.traceId,
                        ctx.spanId,
                        soapResponse,
                        OffsetDateTime.now(),
                        DataUtils.getaNullThrowable(t),
                        MessageTypeEnum.SOAP_END);
            }
        }

        /* -------- helpers ------------------------------------------------ */


        public static String tryEndpointFromStub(Object stub) {
            final String JAX_WS_KEY = "javax.xml.ws.service.endpoint.address";
            final String JAKARTA_KEY = "jakarta.xml.ws.service.endpoint.address";

            try {
                Method m = stub.getClass().getMethod("getRequestContext");
                Object ctxObj = m.invoke(stub);

                if (ctxObj instanceof java.util.Map) {
                    @SuppressWarnings("unchecked")
                    java.util.Map<Object, Object> ctx = (java.util.Map<Object, Object>) ctxObj;

                    Object addr = ctx.get(JAX_WS_KEY);
                    if (addr == null) addr = ctx.get(JAKARTA_KEY);
                    if (addr != null) return addr.toString();
                }
            } catch (Exception ignored) {
            }

            return "<SEIStub>";
        }
    }


    public static class SpringWsAdvice {

        @Advice.OnMethodEnter
        public static MethodCtx onEnter(@Advice.Argument(0) Object request,
                                        @Advice.This Object template) {


            MethodCtx ctx = new MethodCtx();
            ctx.uuidMessage = UuidCreator.getTimeBased().toString();
            ctx.traceId = ContextManager.getTraceId();
            ctx.spanId = ContextManager.getSpanId();

            String operation = extractOperationName(request);

            if (!ContextManager.isMessageIdQueueEmpty()) {
                sendMessageSOAPStart(
                        ctx.uuidMessage,
                        ctx.traceId,
                        ctx.spanId,
                        tryResolveDefaultUri(template),
                        operation,
                        ReflectionUtils.objectToString(request),
                        OffsetDateTime.now(),
                        ContextManager.getMessageIdQueueNew(),
                        MessageTypeEnum.SOAP_START);
            }
            return ctx;
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void onExit(@Advice.Enter MethodCtx ctx,
                                  @Advice.Return Object response,
                                  @Advice.Thrown Throwable t) {

            String soapResponse = ReflectionUtils.objectToString(response);

            if (!ContextManager.isMessageIdQueueEmpty()) {
                sendMessageSOAPEnd(
                        ctx.uuidMessage,
                        ctx.traceId,
                        ctx.spanId,
                        soapResponse,
                        OffsetDateTime.now(),
                        DataUtils.getaNullThrowable(t),
                        MessageTypeEnum.SOAP_END);
            }
        }

        public static String extractOperationName(Object request) {
            if (request == null) return "<unknownOperation>";

            Class<?> targetClass = request.getClass();
            for (Annotation a : targetClass.getAnnotations()) {
                String annName = a.annotationType().getName();
                if ("jakarta.xml.bind.annotation.XmlRootElement".equals(annName)
                        || "javax.xml.bind.annotation.XmlRootElement".equals(annName)) {
                    try {

                        Method nameMethod = a.annotationType().getMethod("name");
                        Object localPart = nameMethod.invoke(a);
                        if (localPart != null && !localPart.toString().isEmpty())
                            return localPart.toString();
                    } catch (Exception ignored) {
                    }
                }
            }

            String simple = targetClass.getSimpleName();
            return simple.replaceAll("(Request|Req)$", "");
        }


        public static String tryResolveDefaultUri(Object t) {
            try {
                String uri = (String) t.getClass()
                        .getMethod("getDefaultUri")
                        .invoke(t);
                return uri != null ? uri : "<WebServiceTemplate>";
            } catch (Exception e) {
                return "<WebServiceTemplate>";
            }
        }
    }


    /* ------------------------------------------------------------------ */
    /*  Apache CXF                                                         */
    /* ------------------------------------------------------------------ */
    public static class CxfAdvice {
        @Advice.OnMethodEnter
        public static MethodCtx onEnter(@Advice.Argument(1) Method m,
                                        @Advice.Argument(2) Object[] args,
                                        @Advice.This Object proxy) {
            MethodCtx ctx = new MethodCtx();
            ctx.uuidMessage = UuidCreator.getTimeBased().toString();
            ctx.traceId = ContextManager.getTraceId();
            ctx.spanId = ContextManager.getSpanId();
            String operation = m.getName();

            String soapRequest = ReflectionUtils.objectToString(args);

            String endpoint = tryExtractEndpoint(proxy);
            if (!ContextManager.isMessageIdQueueEmpty()) {
                sendMessageSOAPStart(
                        ctx.uuidMessage, ctx.traceId, ctx.spanId,
                        endpoint, operation, soapRequest,
                        OffsetDateTime.now(),
                        ContextManager.getMessageIdQueueNew(),
                        MessageTypeEnum.SOAP_START);
            }
            return ctx;
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void onExit(@Advice.Enter MethodCtx ctx,
                                  @Advice.Return Object result,
                                  @Advice.Thrown Throwable t) {


            String soapResult = ReflectionUtils.objectToString(result);

            if (!ContextManager.isMessageIdQueueEmpty()) {
                sendMessageSOAPEnd(
                        ctx.uuidMessage, ctx.traceId, ctx.spanId,
                        soapResult,
                        OffsetDateTime.now(),
                        DataUtils.getaNullThrowable(t),
                        MessageTypeEnum.SOAP_END);
            }
        }
    }


    public static String dumpSoap(Object soapMsg) {
        if (soapMsg == null) return "";
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            Method writeTo = soapMsg.getClass().getMethod("writeTo", java.io.OutputStream.class);
            writeTo.invoke(soapMsg, bos);
            return bos.toString(String.valueOf(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            return "<cannot serialize>";
        }
    }

    public static String tryExtractEndpoint(Object proxy) {
        try {
            Object client = proxy.getClass()
                    .getMethod("getClient")
                    .invoke(proxy);

            Object endpoint = client.getClass()
                    .getMethod("getEndpoint")
                    .invoke(client);

            Object epInfo = endpoint.getClass()
                    .getMethod("getEndpointInfo")
                    .invoke(endpoint);

            Object address = epInfo.getClass()
                    .getMethod("getAddress")
                    .invoke(epInfo);

            return address != null ? address.toString() : "<unknown>";
        } catch (Exception e) {
            return "<unknown>";
        }
    }


    public static class MethodCtx {
        public String uuidMessage;
        public String traceId;
        public String spanId;
    }
}
