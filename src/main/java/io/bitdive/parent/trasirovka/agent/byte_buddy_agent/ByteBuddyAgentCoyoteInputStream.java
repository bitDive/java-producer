package io.bitdive.parent.trasirovka.agent.byte_buddy_agent;

import io.bitdive.parent.trasirovka.agent.utils.LoggerStatusContent;
import io.bitdive.parent.trasirovka.agent.utils.RequestBodyCollector;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.instrument.Instrumentation;

public class ByteBuddyAgentCoyoteInputStream {

    public static ResettableClassFileTransformer init(Instrumentation instrumentation) {
        try {
            return new AgentBuilder.Default()
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    .type(ElementMatchers.named("org.apache.catalina.connector.CoyoteInputStream"))
                    .transform((builder, typeDescription, classLoader, module, dd) ->
                            builder
                                    .visit(Advice.to(ReadIntAdvice.class).on(
                                            ElementMatchers.named("read").and(ElementMatchers.takesNoArguments())
                                    ))
                                    .visit(Advice.to(ReadBytesAdvice.class).on(
                                            ElementMatchers.named("read").and(ElementMatchers.takesArguments(3))
                                    ))
                    )
                    .installOn(instrumentation);
        } catch (Exception e) {
            if (LoggerStatusContent.isErrorsOrDebug()) {
                System.err.println("ByteBuddyAgentCoyoteInputStream init error: " + e.getMessage());
            }
        }
        return null;
    }

    public static class ReadIntAdvice {
        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void onExit(@Advice.Return int result) {
            if (result >= 0) {
                RequestBodyCollector.append((byte) result);
            }
        }
    }

    public static class ReadBytesAdvice {
        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void onExit(@Advice.Argument(0) byte[] b,
                                  @Advice.Argument(1) int off,
                                  @Advice.Argument(2) int len,
                                  @Advice.Return int result) {
            if (result > 0 && b != null) {
                RequestBodyCollector.append(b, off, result);
            }
        }
    }
}


