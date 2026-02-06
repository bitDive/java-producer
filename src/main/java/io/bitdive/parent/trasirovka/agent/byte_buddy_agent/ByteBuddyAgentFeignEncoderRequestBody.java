package io.bitdive.parent.trasirovka.agent.byte_buddy_agent;

import io.bitdive.parent.trasirovka.agent.utils.LoggerStatusContent;
import io.bitdive.parent.trasirovka.agent.utils.OutgoingFeignBodyContext;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.implementation.bind.annotation.AllArguments;
import net.bytebuddy.implementation.bind.annotation.RuntimeType;
import net.bytebuddy.implementation.bind.annotation.SuperCall;
import net.bytebuddy.matcher.ElementMatchers;

import java.util.concurrent.Callable;

/**
 * Captures original request body object for Feign BEFORE serialization.
 *
 * <p>Why: in Feign {@code Client.execute(...)} we see only already encoded bytes. The original DTO is
 * available in {@code feign.codec.Encoder#encode(Object,...)}.
 */
public class ByteBuddyAgentFeignEncoderRequestBody {

    public static AgentBuilder init(AgentBuilder agentBuilder) {
        try {
            Class<?> encoderClass = Class.forName("feign.codec.Encoder");
            return agentBuilder
                    .type(ElementMatchers.isSubTypeOf(encoderClass))
                    .transform((builder, typeDescription, classLoader, module, sd) ->
                            builder
                                    .method(ElementMatchers.named("encode").and(ElementMatchers.takesArguments(3)))
                                    .intercept(MethodDelegation.to(FeignEncoderInterceptor.class))
                    );
        } catch (Exception e) {
            if (LoggerStatusContent.isErrorsOrDebug()) {
                System.err.println("Not found class feign.codec.Encoder in ClassLoader.");
            }
        }
        return agentBuilder;
    }

    public static class FeignEncoderInterceptor {
        @RuntimeType
        public static Object intercept(@SuperCall Callable<?> zuper,
                                       @AllArguments Object[] args) throws Throwable {
            // args[0] is the original body object (DTO) passed to Encoder.encode(...)
            try {
                if (args != null && args.length > 0) {
                    Object bodyObj = args[0];
                    if (bodyObj != null) {
                        OutgoingFeignBodyContext.push(bodyObj);
                    }
                }
            } catch (Exception ignored) {
            }
            return zuper.call();
        }
    }
}

