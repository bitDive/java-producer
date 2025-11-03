package io.bitdive.parent.utils;

import io.bitdive.parent.message_producer.HttpsURLConnectionCustom;
import io.bitdive.parent.message_producer.LocalCryptoService;
import io.bitdive.parent.parserConfig.YamlParserConfig;
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
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class UtilsDataConvert {
    private final static Map<String, Pair<MethodTypeEnum, Boolean>> mapNewSpan = new ConcurrentHashMap<>();
    private final static Map<String, Boolean> mapStaticMethod = new ConcurrentHashMap<>();

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
            boolean isNewSpan = false;
            MethodTypeEnum methodType = MethodTypeEnum.METHOD;
            boolean foundMethodType = false;

            // 1. Check annotations from the method itself first (most common case)
            Annotation[] annotations = method.getAnnotations();

            // 2. Process method annotations with early exit optimization
            for (Annotation annotation : annotations) {
                String annotationTypeName = annotation.annotationType().getName();

                // Fast path: check most common Spring Web annotations
                if (annotationTypeName.startsWith("org.springframework.web.bind.annotation")) {
                    isNewSpan = true;

                    // Extract method type from annotation name
                    if (!foundMethodType) {
                        for (MethodTypeEnum methodTypeEnum : MethodTypeEnum.getListMethodFlagInPoint()) {
                            String annName = methodTypeEnum.getAnnotationName();
                            if (!annName.isEmpty() && annotationTypeName.contains(annName)) {
                                methodType = methodTypeEnum;
                                foundMethodType = true;
                                break;
                            }
                        }

                        // Handle @RequestMapping specially
                        if (!foundMethodType && annotationTypeName.contains("RequestMapping")) {
                            MethodTypeEnum detectedType = detectRequestMappingType(annotation);
                            if (detectedType != MethodTypeEnum.METHOD) {
                                methodType = detectedType;
                                foundMethodType = true;
                            }
                        }
                    }

                    // Early exit if we found both flags
                    if (isNewSpan && foundMethodType) {
                        return new Pair<>(methodType, isNewSpan);
                    }
                }
                // Check Scheduled annotation
                else if (annotationTypeName.contains("Scheduled")) {
                    isNewSpan = true;
                    if (!foundMethodType && annotationTypeName.contains("Scheduled")) {
                        methodType = MethodTypeEnum.SCHEDULER;
                        foundMethodType = true;
                    }
                    if (foundMethodType) {
                        return new Pair<>(methodType, isNewSpan);
                    }
                }
            }

            // 3. If not found on method, check interfaces (only if necessary)
            if (!isNewSpan || !foundMethodType) {
                Class<?> declaringClass = method.getDeclaringClass();
                Class<?>[] interfaces = declaringClass.getInterfaces();

                // Optimize: only check interfaces if there are any
                if (interfaces.length > 0) {
                    for (Class<?> interfaceClass : interfaces) {
                        try {
                            Method interfaceMethod = interfaceClass.getMethod(method.getName(), method.getParameterTypes());
                            Annotation[] interfaceAnnotations = interfaceMethod.getAnnotations();

                            for (Annotation annotation : interfaceAnnotations) {
                                String annotationTypeName = annotation.annotationType().getName();

                                if (annotationTypeName.startsWith("org.springframework.web.bind.annotation")) {
                                    isNewSpan = true;

                                    if (!foundMethodType) {
                                        for (MethodTypeEnum methodTypeEnum : MethodTypeEnum.getListMethodFlagInPoint()) {
                                            String annName = methodTypeEnum.getAnnotationName();
                                            if (!annName.isEmpty() && annotationTypeName.contains(annName)) {
                                                methodType = methodTypeEnum;
                                                foundMethodType = true;
                                                break;
                                            }
                                        }

                                        if (!foundMethodType && annotationTypeName.contains("RequestMapping")) {
                                            MethodTypeEnum detectedType = detectRequestMappingType(annotation);
                                            if (detectedType != MethodTypeEnum.METHOD) {
                                                methodType = detectedType;
                                                foundMethodType = true;
                                            }
                                        }
                                    }

                                    if (isNewSpan && foundMethodType) {
                                        return new Pair<>(methodType, isNewSpan);
                                    }
                                }
                            }
                        } catch (NoSuchMethodException e) {
                            // Method not in this interface, continue
                        }

                        // Early exit if found both in any interface
                        if (isNewSpan && foundMethodType) {
                            break;
                        }
                    }
                }
            }

            // 4. Last resort: check parameter annotations (only if still not a new span)
            if (!isNewSpan) {
                Annotation[][] parameterAnnotations = method.getParameterAnnotations();
                if (parameterAnnotations.length > 0) {
                    for (Annotation[] paramAnnotations : parameterAnnotations) {
                        for (Annotation paramAnnotation : paramAnnotations) {
                            if (paramAnnotation.annotationType().getName().startsWith("org.springframework.web.bind.annotation")) {
                                isNewSpan = true;
                                return new Pair<>(methodType, isNewSpan);
                            }
                        }
                    }
                }
            }

            return new Pair<>(methodType, isNewSpan);
        });
    }


    private static MethodTypeEnum detectRequestMappingType(Annotation annotation) {
        try {
            // Try to get the "method" attribute from @RequestMapping
            Method methodAttr = annotation.annotationType().getMethod("method");
            Object methodValue = methodAttr.invoke(annotation);

            if (methodValue != null && methodValue.getClass().isArray()) {
                Object[] methods = (Object[]) methodValue;
                if (methods.length > 0) {
                    String methodName = methods[0].toString();

                    if (methodName.contains("GET")) return MethodTypeEnum.WEB_GET;
                    if (methodName.contains("POST")) return MethodTypeEnum.WEB_POST;
                    if (methodName.contains("PUT")) return MethodTypeEnum.WEB_PUT;
                    if (methodName.contains("DELETE")) return MethodTypeEnum.WEB_DELETE;
                    if (methodName.contains("PATCH")) return MethodTypeEnum.WEB_PATCH;
                }
            }
        } catch (Exception e) {
            // If we can't determine the method type, just return METHOD
            if (LoggerStatusContent.isDebug()) {
                System.err.println("Could not detect RequestMapping method type: " + e.getMessage());
            }
        }

        return MethodTypeEnum.METHOD;
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

            byte[] base64Data = Base64.getEncoder().encode(readFileBytes(file));
            //base64Data = fileBytes);

            // Encrypt the file data
            Pair<Integer, byte[]> encryptedData = /*isSSLSend ? LocalCryptoService.encrypt(base64Data) :*/ Pair.createPair(-1, base64Data);

            // Sign the encrypted data
            Pair<Integer, byte[]> signature = LocalCryptoService.sign(base64Data);

            URL serverUrl = new URL(YamlParserConfig.getProfilingConfig().getMonitoring().getSendFiles().getServerConsumer().getUrl() + "/" + methodOnServer);

            // Get and evaluate the response code
            int responseCode =
                    HttpsURLConnectionCustom.sentToServer((HttpsURLConnection) serverUrl.openConnection(proxy), file, encryptedData, signature);

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
