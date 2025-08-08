package io.bitdive.parent.trasirovka.agent.utils;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;


public class RestUtils {


    public static String normalizeResponseBodyBytes(byte[] responseBodyBytes, Object responseHeaders, Charset responseCharset) {
        if (responseBodyBytes == null || responseBodyBytes.length == 0) {
            return "";
        }
        // Если по заголовкам определяем, что это файл
        if (isFileResponse(responseHeaders)) {
            return "[file]";
        }
        // Если контент определяется как бинарный, возвращаем заглушку
        else if (isBinaryContent(responseHeaders)) {
            return "[byte array]";
        }
        // Иначе преобразуем массив байт в строку с использованием заданного charset
        return new String(responseBodyBytes, responseCharset);
    }

    private static boolean isFileResponse(Object headers) {
        if (headers == null) return false;

        try {
            if (headers instanceof Map) {
                Map<?, ?> map = (Map<?, ?>) headers;

                for (Object keyObj : map.keySet()) {
                    String headerName = keyObj.toString();
                    if ("content-disposition".equalsIgnoreCase(headerName)) {
                        Object value = map.get(headerName);
                        String headerValue = null;

                        if (value instanceof List) {
                            List<?> list = (List<?>) value;
                            if (!list.isEmpty()) {
                                headerValue = String.valueOf(list.get(0));
                            }
                        } else if (value != null) {
                            headerValue = value.toString();
                        }

                        if (headerValue != null && headerValue.toLowerCase().contains("attachment")) {
                            return true;
                        }
                    }
                }
            } else {
                // Если не Map, пытаемся использовать reflection (например, для Spring HttpHeaders)
                Method keySetMethod = headers.getClass().getMethod("keySet");
                Method getFirstMethod = headers.getClass().getMethod("getFirst", String.class);

                @SuppressWarnings("unchecked")
                Set<String> headerNames = (Set<String>) keySetMethod.invoke(headers);

                for (String headerName : headerNames) {
                    if ("content-disposition".equalsIgnoreCase(headerName)) {
                        Object headerValue = getFirstMethod.invoke(headers, headerName);
                        if (headerValue != null && headerValue.toString().toLowerCase().contains("attachment")) {
                            return true;
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Лог при необходимости
            // e.printStackTrace();
        }

        return false;
    }

    private static boolean isBinaryContent(Object headers) {
        if (headers == null) return false;

        try {
            // Пробуем как Map (например, UnmodifiableMap<String, List<String>>)
            if (headers instanceof Map) {
                Map<?, ?> map = (Map<?, ?>) headers;

                for (Object keyObj : map.keySet()) {
                    String headerName = keyObj.toString();
                    if ("content-type".equalsIgnoreCase(headerName)) {
                        Object value = map.get(headerName);
                        String contentType = null;

                        if (value instanceof List) {
                            List<?> list = (List<?>) value;
                            if (!list.isEmpty()) {
                                contentType = String.valueOf(list.get(0));
                            }
                        } else if (value != null) {
                            contentType = value.toString();
                        }

                        if (contentType != null) {
                            String type = contentType.toLowerCase();
                            if (!type.startsWith("text") && !type.contains("json") && !type.contains("xml")) {
                                return true;
                            }
                        }
                    }
                }
            } else {
                // Пробуем вызвать метод getContentType() через reflection
                Method getContentTypeMethod = headers.getClass().getMethod("getContentType");
                getContentTypeMethod.setAccessible(true);
                Object mediaType = getContentTypeMethod.invoke(headers);
                if (mediaType != null) {
                    String type = mediaType.toString().toLowerCase();
                    if (!type.startsWith("text") && !type.contains("json") && !type.contains("xml")) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            // Если ошибка — считаем, что контент бинарный
            return true;
        }

        return false;
    }


    public static String normalizeRequestBody(Object bodyObj) {
        if (bodyObj == null) return "";
        // Если это массив байт
        if (bodyObj.getClass().isArray() && bodyObj.getClass().getComponentType() == byte.class) {
            return "[byte array]";
        }
        // Если передаётся файл, MultipartFile или Resource (проверка по имени класса)
        if (bodyObj instanceof File
                || bodyObj.getClass().getName().equals("org.springframework.web.multipart.MultipartFile")
                || bodyObj.getClass().getName().equals("org.springframework.core.io.Resource")) {
            return "[file]";
        }
        return ReflectionUtils.objectToString(bodyObj);
    }
}
