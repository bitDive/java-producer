package io.bitdive.parent.trasirovka.agent.byte_buddy_agent;

import io.bitdive.parent.trasirovka.agent.utils.LoggerStatusContent;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.implementation.FieldAccessor;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.implementation.bind.annotation.RuntimeType;
import net.bytebuddy.implementation.bind.annotation.SuperCall;
import net.bytebuddy.implementation.bind.annotation.This;
import net.bytebuddy.matcher.ElementMatchers;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.Callable;

public class ByteBuddySimpleClientHttpResponse {

    /**
     * View injected into {@code ClientHttpResponse} implementations to access cached body without reflection.
     */
    public interface BitDiveSimpleClientHttpResponseView {
        byte[] bitdiveCachedBody();
        void bitdiveSetCachedBody(byte[] bytes);
    }

    public static AgentBuilder  init(AgentBuilder agentBuilder) {
        try {
            Class<?> clientClass = Class.forName("org.springframework.http.client.ClientHttpResponse");
            return new AgentBuilder.Default()
                    .type(ElementMatchers.nameContains("org.springframework").and(ElementMatchers.isSubTypeOf(clientClass)))
                    .transform((builder, typeDescription, classLoader, module, dd) ->
                            builder.defineField("cachedBody", byte[].class, Visibility.PRIVATE)
                                    .implement(BitDiveSimpleClientHttpResponseView.class)
                                    .defineMethod("bitdiveCachedBody", byte[].class, Visibility.PUBLIC)
                                    .intercept(FieldAccessor.ofField("cachedBody"))
                                    .defineMethod("bitdiveSetCachedBody", void.class, Visibility.PUBLIC)
                                    .withParameters(byte[].class)
                                    .intercept(FieldAccessor.ofField("cachedBody"))
                                    .method(ElementMatchers.named("getBody"))
                                    .intercept(MethodDelegation.to(GetBodyInterceptor.class))
                    );
        } catch (Exception e) {
            if (LoggerStatusContent.isErrorsOrDebug())
                System.err.println("Not found class feign.Client in ClassLoader.");
        }
        return agentBuilder;
    }


    public static class GetBodyInterceptor {

        @RuntimeType
        public static InputStream intercept(@SuperCall Callable<InputStream> zuper, @This Object obj) throws Exception {
            if (!(obj instanceof BitDiveSimpleClientHttpResponseView)) {
                return zuper.call();
            }
            BitDiveSimpleClientHttpResponseView view = (BitDiveSimpleClientHttpResponseView) obj;
            byte[] cachedBody = view.bitdiveCachedBody();

            if (cachedBody != null) {
                return new ByteArrayInputStream(cachedBody);
            }

            InputStream originalInputStream = zuper.call();
            byte[] responseBodyBytes = readAllBytes(originalInputStream);
            view.bitdiveSetCachedBody(responseBodyBytes);
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
