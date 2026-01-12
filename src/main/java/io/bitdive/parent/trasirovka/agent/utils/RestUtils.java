package io.bitdive.parent.trasirovka.agent.utils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class RestUtils {

    public static String normalizeResponseBodyBytes(byte[] responseBodyBytes, Object responseHeaders,
            Charset responseCharset) {
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
        String raw = new String(responseBodyBytes, responseCharset);
        if (isJsonContent(responseHeaders)) {
            return ReflectionUtils.tryNormalizeJsonStringWithClass(raw);
        }
        return raw;
    }

    private static boolean isJsonContent(Object headers) {
        String contentType = extractContentType(headers);
        if (contentType == null) return false;
        String type = contentType.toLowerCase();
        return type.contains("json") || type.contains("+json");
    }

    private static String extractContentType(Object headers) {
        if (headers == null) return null;
        try {
            if (headers instanceof Map) {
                Map<?, ?> map = (Map<?, ?>) headers;
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    if (e.getKey() == null) continue;
                    String headerName = String.valueOf(e.getKey());
                    if (!"content-type".equalsIgnoreCase(headerName)) continue;
                    Object v = e.getValue();
                    if (v instanceof List) {
                        List<?> list = (List<?>) v;
                        if (!list.isEmpty() && list.get(0) != null) return String.valueOf(list.get(0));
                    } else if (v != null) {
                        return String.valueOf(v);
                    }
                }
                return null;
            }

            // Spring HttpHeaders: getContentType() -> MediaType
            try {
                Method getContentTypeMethod = headers.getClass().getMethod("getContentType");
                getContentTypeMethod.setAccessible(true);
                Object mediaType = getContentTypeMethod.invoke(headers);
                return mediaType != null ? mediaType.toString() : null;
            } catch (Exception ignored) {
                // ignore and try getFirst
            }

            // Fallback: getFirst("Content-Type")
            try {
                Method getFirstMethod = headers.getClass().getMethod("getFirst", String.class);
                Object v = getFirstMethod.invoke(headers, "Content-Type");
                return v != null ? v.toString() : null;
            } catch (Exception ignored) {
                return null;
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean isFileResponse(Object headers) {
        if (headers == null)
            return false;

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
                // Если не Map, пытаемся использовать reflection (например, для Spring
                // HttpHeaders)
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
        if (headers == null)
            return false;

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
        if (bodyObj == null)
            return "";

        // Специальная обработка для FastByteArrayOutputStream (Spring 3+)
        if (bodyObj.getClass().getName().equals("org.springframework.util.FastByteArrayOutputStream")) {
            try {
                Method toByteArrayMethod = bodyObj.getClass().getMethod("toByteArray");
                toByteArrayMethod.setAccessible(true);
                byte[] bytes = (byte[]) toByteArrayMethod.invoke(bodyObj);
                // Преобразуем байты в строку
                if (bytes != null && bytes.length > 0) {
                    return new String(bytes, Charset.defaultCharset());
                }
                return "";
            } catch (Exception e) {
                return "[Error reading FastByteArrayOutputStream: " + e.getMessage() + "]";
            }
        }

        // Специальная обработка для ByteArrayOutputStream (Spring 2)
        // В Spring 2 getBody() может возвращать java.io.ByteArrayOutputStream
        if (bodyObj instanceof ByteArrayOutputStream) {
            try {
                byte[] bytes = ((ByteArrayOutputStream) bodyObj).toByteArray();
                // Преобразуем байты в строку
                if (bytes != null && bytes.length > 0) {
                    return new String(bytes, Charset.defaultCharset());
                }
                return "";
            } catch (Exception e) {
                return "[Error reading ByteArrayOutputStream: " + e.getMessage() + "]";
            }
        }

        // Если это массив байт
        if (bodyObj.getClass().isArray() && bodyObj.getClass().getComponentType() == byte.class) {
            byte[] bytes = (byte[]) bodyObj;
            if (bytes.length > 0) {
                return new String(bytes, Charset.defaultCharset());
            }
            return "";
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
