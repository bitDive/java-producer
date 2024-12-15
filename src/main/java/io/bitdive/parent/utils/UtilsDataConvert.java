package io.bitdive.parent.utils;

import io.bitdive.parent.message_producer.HttpURLConnectionCustom;
import io.bitdive.parent.message_producer.HttpsURLConnectionCustom;
import io.bitdive.parent.message_producer.LocalCryptoService;
import io.bitdive.parent.parserConfig.YamlParserConfig;
import io.bitdive.parent.safety_config.SSLContextCustomBitDive;
import io.bitdive.parent.trasirovka.agent.utils.LoggerStatusContent;

import javax.net.ssl.HttpsURLConnection;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.*;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class UtilsDataConvert {
    private final static Map<String, Pair<MethodTypeEnum, Boolean>> mapNewSpan = new ConcurrentHashMap<>();
    private final static Map<String, Boolean> mapStaticMethod = new ConcurrentHashMap<>();
    private final static List<String> listNewFlagComponent = Arrays.asList("org.springframework.web.bind.annotation", "Scheduled");

    public static Object handleResult(Object result) {
        return result;
    }

    public static Boolean isStaticMethod(Method method) {
        String findClassAndMethod = String.format("%s//%s", method.getDeclaringClass().getName(), method.getName());
        return mapStaticMethod.computeIfAbsent(findClassAndMethod, key -> Modifier.isStatic(method.getModifiers()));
    }

    public static boolean isSerializationContext() {
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        for (StackTraceElement element : stackTrace) {
            if (element.getClassName().contains("io.bitdive.parent.trasirovka.agent.utils.ReflectionUtils"))
                return true;
        }
        return false;
    }

    public static Pair<MethodTypeEnum, Boolean> identificationMethod(Method method) {
        String findClassAndMethod = String.format("%s//%s", method.getDeclaringClass().getName(), method.getName());

        return mapNewSpan.computeIfAbsent(findClassAndMethod, key -> {
            Annotation[] annotations = method.getDeclaredAnnotations();

            boolean isNewSpan = false;
            MethodTypeEnum methodType = MethodTypeEnum.METHOD;
            for (Annotation annotation : annotations) {

                if (listNewFlagComponent.stream().anyMatch(s -> annotation.annotationType().getPackage().getName().contains(s))) {
                    isNewSpan = true;
                }
                for (MethodTypeEnum methodTypeEnum : MethodTypeEnum.getListMethodFlagInPoint()) {
                    if (annotation.annotationType().getName().contains(methodTypeEnum.getAnnotationName())) {
                        methodType = methodTypeEnum;
                        break;
                    }
                }
            }

            return new Pair<>(methodType, isNewSpan);
        });
    }


    private static byte[] readFileBytes(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                bos.write(buffer, 0, bytesRead);
            }
            return bos.toByteArray();
        }
    }

    public static boolean sendFile(File file, Proxy proxy, String methodOnServer) {
        try {
            boolean isSSLSend = YamlParserConfig.getProfilingConfig().getMonitoring().getSendFiles().getServerConsumer().isSSLSend();
            if (isSSLSend) SSLContextCustomBitDive.ensureValidCertificate();

            byte[] fileBytes = readFileBytes(file);
            String base64Data = Base64.getEncoder().encodeToString(fileBytes);

            // Encrypt the file data
            Pair<Integer, String> encryptedData = isSSLSend ? LocalCryptoService.encrypt(base64Data) : Pair.createPair(-1, base64Data);

            // Sign the encrypted data
            Pair<Integer, String> signature = isSSLSend ? LocalCryptoService.sign(encryptedData.getVal()) : Pair.createPair(-1, "");

            URL serverUrl = new URL(YamlParserConfig.getProfilingConfig().getMonitoring().getSendFiles().getServerConsumer().getUrl() + "/" + methodOnServer);

            // Get and evaluate the response code
            int responseCode = isSSLSend ?
                    HttpsURLConnectionCustom.sentToServer((HttpsURLConnection) serverUrl.openConnection(proxy), file, encryptedData, signature) :
                    HttpURLConnectionCustom.sentToServer((HttpURLConnection) serverUrl.openConnection(proxy), file, encryptedData, signature);

            if (responseCode == HttpsURLConnection.HTTP_OK) {
                if (LoggerStatusContent.isDebug())
                    System.out.println("File uploaded successfully: " + file.getName());
                return true;
            } else {
                if (LoggerStatusContent.isDebug())
                    System.err.println("Failed to upload file. Response code: " + responseCode);
                return false;
            }
        } catch (IOException e) {
            if (LoggerStatusContent.isErrorsOrDebug())
                System.err.println("Failed to upload file: " + e);
            return false;
        } catch (Exception e) {

            System.err.println("An error occurred: " + e);
            return false;
        }
    }

    public static Proxy initilizationProxy(String proxyHost, String proxyPort, String proxyUserName, String proxyPassword) {
        Proxy proxy = Proxy.NO_PROXY;
        if (proxyHost != null && proxyPort != null) {
            proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, Integer.parseInt(proxyPort)));
            if (proxyUserName != null && proxyPassword != null && !proxyUserName.isEmpty() && !proxyPassword.isEmpty()) {
                Authenticator.setDefault(new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        if (getRequestorType() == RequestorType.PROXY) {
                            return new PasswordAuthentication(proxyUserName, proxyPassword.toCharArray());
                        }
                        return super.getPasswordAuthentication();
                    }
                });
            }
        }
        return proxy;
    }
}
