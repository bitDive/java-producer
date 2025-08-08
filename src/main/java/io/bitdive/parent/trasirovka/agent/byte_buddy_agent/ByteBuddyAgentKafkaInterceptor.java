package io.bitdive.parent.trasirovka.agent.byte_buddy_agent;

import io.bitdive.parent.parserConfig.YamlParserConfig;
import io.bitdive.parent.trasirovka.agent.utils.KafkaAgentStorage;
import io.bitdive.parent.trasirovka.agent.utils.LoggerStatusContent;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.implementation.bind.annotation.AllArguments;
import net.bytebuddy.implementation.bind.annotation.Origin;
import net.bytebuddy.implementation.bind.annotation.RuntimeType;
import net.bytebuddy.implementation.bind.annotation.SuperCall;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;

import static io.bitdive.parent.message_producer.MessageService.sendMessageCriticalKafkaError;

public class ByteBuddyAgentKafkaInterceptor {

    private static final Map<Object, String> NC_BOOTSTRAP_MAP = new ConcurrentHashMap<>();

    public static ResettableClassFileTransformer init(Instrumentation instrumentation) {
        return new AgentBuilder.Default()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .type(ElementMatchers.named("org.apache.kafka.clients.NetworkClient"))
                .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                        builder
                                .method(ElementMatchers.named("processDisconnection"))
                                .intercept(MethodDelegation.to(ProcessDisconnectionInterceptor.class))
                )
                .installOn(instrumentation);
    }

    public static class ProcessDisconnectionInterceptor {

        @RuntimeType
        public static Object intercept(@Origin Method method,
                                       @SuperCall Callable<?> zuper,
                                       @AllArguments Object[] args) throws Exception {

            if (LoggerStatusContent.getEnabledProfile()) return zuper.call();

            Object result = null;
            try {
                result = zuper.call();
            } catch (Throwable t) {
                if (LoggerStatusContent.isErrorsOrDebug())
                    System.err.println("ByteBuddyAgentKafkaInterceptor ERROR (calling original method): " + t.getMessage());
            }

            try {
                if (args != null && args.length >= 4) {
                    String nodeId = (args[1] instanceof String) ? (String) args[1] : null;
                    Object disconnectState = args[3];

                    if (disconnectState != null) {
                        handleDisconnectState(nodeId, disconnectState);
                    }
                }
            } catch (Throwable t) {
                if (LoggerStatusContent.isErrorsOrDebug())
                    System.err.println("ByteBuddyAgentKafkaInterceptor ERROR (custom logic): " + t.getMessage());
            }

            return result;
        }


        private static void handleDisconnectState(String nodeId, Object disconnectState) {
            try {
                Method getStateMethod = disconnectState.getClass().getMethod("state");
                Object stateEnum = getStateMethod.invoke(disconnectState); // enum: AUTHENTICATION_FAILED, AUTHENTICATE, NOT_CONNECTED, ...
                String stateName = (stateEnum != null) ? stateEnum.toString() : "";
                String remoteAddrStr = KafkaAgentStorage.getBootstrap();
                Method getExceptionMethod = disconnectState.getClass().getMethod("exception");
                Object exceptionObj = getExceptionMethod.invoke(disconnectState);

                String exceptionMsg = (exceptionObj instanceof Throwable)
                        ? ((Throwable) exceptionObj).getMessage()
                        : null;

                switch (stateName) {
                    case "AUTHENTICATION_FAILED":
                        sendMessageCriticalKafkaError(remoteAddrStr,
                                String.format("Connection to node %s (%s) failed authentication due to: %s",
                                        nodeId, remoteAddrStr, (exceptionMsg != null ? exceptionMsg : "unknown error"))
                        );
                        break;
                    case "AUTHENTICATE":
                        sendMessageCriticalKafkaError(remoteAddrStr,
                                String.format("Connection to node %s (%s) terminated during authentication. Possible reasons: " +
                                                "(1) Authentication failed due to invalid credentials with brokers older than 1.0.0, " +
                                                "(2) Firewall blocking Kafka TLS traffic, (3) Transient network issue.",
                                        nodeId, remoteAddrStr)
                        );
                        break;
                    case "NOT_CONNECTED":
                        sendMessageCriticalKafkaError(remoteAddrStr,
                                String.format("Connection to node %s (%s) could not be established. Node may not be available.",
                                        nodeId, remoteAddrStr)
                        );
                        break;
                    default:
                        break;
                }
            } catch (NoSuchMethodException e) {
                if (LoggerStatusContent.isErrorsOrDebug())
                    System.err.println("ByteBuddyAgentKafkaInterceptor ERROR: No such method on disconnectState: " + e.getMessage());
            } catch (Exception e) {
                if (LoggerStatusContent.isErrorsOrDebug())
                    System.err.println("ByteBuddyAgentKafkaInterceptor ERROR while handling disconnectState: " + e.getMessage());
            }
        }

    }
}
