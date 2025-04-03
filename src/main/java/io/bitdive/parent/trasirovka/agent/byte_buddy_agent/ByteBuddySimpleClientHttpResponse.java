package io.bitdive.parent.trasirovka.agent.byte_buddy_agent;

import io.bitdive.parent.trasirovka.agent.utils.LoggerStatusContent;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.implementation.bind.annotation.RuntimeType;
import net.bytebuddy.implementation.bind.annotation.SuperCall;
import net.bytebuddy.implementation.bind.annotation.This;
import net.bytebuddy.matcher.ElementMatchers;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.util.concurrent.Callable;

public class ByteBuddySimpleClientHttpResponse {
    public static void init(Instrumentation instrumentation) {
        try {
            Class<?> clientClass = Class.forName("org.springframework.http.client.ClientHttpResponse");
            new AgentBuilder.Default()
                    .type(ElementMatchers.nameContains("org.springframework").and(ElementMatchers.isSubTypeOf(clientClass)))
                    .transform((builder, typeDescription, classLoader, module, dd) ->
                            builder.defineField("cachedBody", byte[].class, Visibility.PRIVATE)
                                    .method(ElementMatchers.named("getBody"))
                                    .intercept(MethodDelegation.to(GetBodyInterceptor.class))
                    ).installOn(instrumentation);
        } catch (Exception e) {
            if (LoggerStatusContent.isErrorsOrDebug())
                System.err.println("Not found class feign.Client in ClassLoader.");
        }
    }


    public static class GetBodyInterceptor {

        @RuntimeType
        public static InputStream intercept(
                @SuperCall Callable<InputStream> zuper,
                @This Object obj) throws Exception {

            Field cachedBodyField = obj.getClass().getDeclaredField("cachedBody");
            cachedBodyField.setAccessible(true);
            byte[] cachedBody = (byte[]) cachedBodyField.get(obj);

            if (cachedBody != null) {
                return new ByteArrayInputStream(cachedBody);
            }

            InputStream originalInputStream = zuper.call();
            byte[] responseBodyBytes = readAllBytes(originalInputStream);
            cachedBodyField.set(obj, responseBodyBytes);
            return new ByteArrayInputStream(responseBodyBytes);
        }

        private static byte[] readAllBytes(InputStream inputStream) throws IOException {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            int nRead;
            byte[] data = new byte[16384]; // 16KB буфер
            while ((nRead = inputStream.read(data, 0, data.length)) != -1) {
                buffer.write(data, 0, nRead);
            }
            return buffer.toByteArray();
        }
    }
}
