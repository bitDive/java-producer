package io.bitdive.parent.trasirovka.agent.utils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class RestUtils {

    private RestUtils() {
    }

    public static String normalizeResponseBodyBytes(byte[] responseBodyBytes,
                                                    Object responseHeaders,
                                                    Charset responseCharset) {
        if (responseBodyBytes == null || responseBodyBytes.length == 0) {
            return "";
        }

        String contentDisposition = getHeaderFirst(responseHeaders, "Content-Disposition");
        String contentType = cleanContentType(getHeaderFirst(responseHeaders, "Content-Type"));

        if (isFileResponse(contentDisposition)) {
            return "[file]";
        }

        if (isBinaryContentType(contentType)) {
            return "[byte array]";
        }

        Charset cs = (responseCharset != null ? responseCharset : StandardCharsets.UTF_8);
        String raw = new String(responseBodyBytes, cs);

        if (isJsonContentType(contentType)) {
            return ReflectionUtils.tryNormalizeJsonStringWithClass(raw);
        }

        return raw;
    }

    public static String normalizeRequestBody(Object bodyObj) {
        if (bodyObj == null) return "";

        // строки
        if (bodyObj instanceof CharSequence) {
            return bodyObj.toString();
        }

        // byte[]
        if (bodyObj instanceof byte[]) {
            byte[] bytes = (byte[]) bodyObj;
            return bytes.length == 0 ? "" : new String(bytes, Charset.defaultCharset());
        }

        // ByteArrayOutputStream (Spring 2) / FastByteArrayOutputStream (Spring 3+) и любые аналоги с toByteArray()
        if (bodyObj instanceof ByteArrayOutputStream || hasToByteArray(bodyObj)) {
            byte[] bytes = tryInvokeToByteArray(bodyObj);
            return (bytes == null || bytes.length == 0) ? "" : new String(bytes, Charset.defaultCharset());
        }

        // файл, MultipartFile, Resource
        if (bodyObj instanceof File
                || isInstanceOf(bodyObj, "org.springframework.web.multipart.MultipartFile")
                || isInstanceOf(bodyObj, "org.springframework.core.io.Resource")) {
            return "[file]";
        }

        return ReflectionUtils.objectToString(bodyObj);
    }

    // -------------------------
    // Content-Type / headers
    // -------------------------

    private static boolean isJsonContentType(String contentType) {
        if (contentType == null) return false;
        String ct = contentType.toLowerCase(Locale.ROOT);
        return ct.contains("json") || ct.contains("+json");
    }

    private static boolean isBinaryContentType(String contentType) {
        if (isBlank(contentType)) {
            // не смогли определить — НЕ считаем бинарём (иначе будет куча ложных [byte array])
            return false;
        }

        String ct = contentType.toLowerCase(Locale.ROOT);

        int semicolon = ct.indexOf(';');
        if (semicolon > 0) ct = ct.substring(0, semicolon).trim();

        // текстовые
        if (ct.startsWith("text/")) return false;

        // json / xml (включая vendor-типы)
        if (ct.contains("json") || ct.contains("+json")) return false;
        if (ct.contains("xml") || ct.contains("+xml")) return false;

        // частые "текстовые" application/*
        if ("application/x-www-form-urlencoded".equals(ct)) return false;
        if ("application/javascript".equals(ct)) return false;
        if ("application/ecmascript".equals(ct)) return false;
        if ("application/graphql".equals(ct)) return false;

        // всё остальное считаем бинарным (application/octet-stream, image/*, audio/*, video/*, pdf и т.д.)
        return true;
    }

    private static boolean isFileResponse(String contentDisposition) {
        if (contentDisposition == null) return false;
        String cd = contentDisposition.toLowerCase(Locale.ROOT);
        // attachment или явное имя файла
        return cd.contains("attachment") || cd.contains("filename=");
    }

    /**
     * Универсально достаёт первый header value:
     * - Map<String, List<String>> (HttpURLConnection#getHeaderFields, etc)
     * - Spring HttpHeaders: getFirst(String), getContentType()
     * - JDK HttpClient: firstValue(String) -> Optional<String>
     * - OkHttp Headers: get(String)
     */
    private static String getHeaderFirst(Object headers, String name) {
        if (headers == null || name == null) return null;

        // Map (важно: у HttpURLConnection map может содержать null-key!)
        if (headers instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) headers;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                Object k = e.getKey();
                if (k == null) continue;
                if (!name.equalsIgnoreCase(String.valueOf(k))) continue;

                Object v = e.getValue();
                return firstValueFromUnknown(v);
            }
            return null;
        }

        // Spring HttpHeaders: getFirst("Content-Type")
        String v = invokeString(headers, "getFirst", new Class[]{String.class}, new Object[]{name});
        if (v != null) return v;

        // JDK HttpClient HttpHeaders: firstValue("Content-Type") -> Optional<String>
        Object opt = invoke(headers, "firstValue", new Class[]{String.class}, new Object[]{name});
        if (opt instanceof Optional) {
            Optional<?> o = (Optional<?>) opt;
            if (o.isPresent() && o.get() != null) return String.valueOf(o.get());
        }

        // OkHttp Headers: get("Content-Type")
        v = invokeString(headers, "get", new Class[]{String.class}, new Object[]{name});
        if (v != null) return v;

        // Spring HttpHeaders: getContentType() -> MediaType
        if ("Content-Type".equalsIgnoreCase(name)) {
            Object mediaType = invoke(headers, "getContentType", new Class[]{}, new Object[]{});
            if (mediaType != null) return String.valueOf(mediaType);
        }

        return null;
    }

    private static String firstValueFromUnknown(Object v) {
        if (v == null) return null;

        // иногда уже прилетает String "[text/plain;...]" (toString от List)
        if (v instanceof String) return (String) v;

        // Iterable (List и т.п.)
        if (v instanceof Iterable) {
            Iterator<?> it = ((Iterable<?>) v).iterator();
            if (it.hasNext()) {
                Object first = it.next();
                return first != null ? String.valueOf(first) : null;
            }
            return null;
        }

        // массив
        if (v.getClass().isArray()) {
            int len = java.lang.reflect.Array.getLength(v);
            if (len > 0) {
                Object first = java.lang.reflect.Array.get(v, 0);
                return first != null ? String.valueOf(first) : null;
            }
            return null;
        }

        return String.valueOf(v);
    }

    private static String cleanContentType(String ct) {
        if (ct == null) return null;

        ct = ct.trim();

        // если прилетело как "[text/plain;charset=utf-8]" (toString() от List)
        if (ct.length() >= 2 && ct.charAt(0) == '[' && ct.charAt(ct.length() - 1) == ']') {
            ct = ct.substring(1, ct.length() - 1).trim();
        }

        return isBlank(ct) ? null : ct;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    // -------------------------
    // Reflection helpers
    // -------------------------

    private static Object invoke(Object target, String method, Class<?>[] paramTypes, Object[] args) {
        try {
            Method m = target.getClass().getMethod(method, paramTypes);
            m.setAccessible(true);
            return m.invoke(target, args);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String invokeString(Object target, String method, Class<?>[] paramTypes, Object[] args) {
        Object v = invoke(target, method, paramTypes, args);
        return v != null ? String.valueOf(v) : null;
    }

    private static boolean hasToByteArray(Object obj) {
        try {
            obj.getClass().getMethod("toByteArray");
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static byte[] tryInvokeToByteArray(Object obj) {
        try {
            Method m = obj.getClass().getMethod("toByteArray");
            m.setAccessible(true);
            Object v = m.invoke(obj);
            return (v instanceof byte[]) ? (byte[]) v : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isInstanceOf(Object obj, String fqcn) {
        if (obj == null || fqcn == null) return false;
        try {
            ClassLoader cl = obj.getClass().getClassLoader();
            Class<?> type = Class.forName(fqcn, false, cl);
            return type.isInstance(obj);
        } catch (Throwable ignored) {
            return false;
        }
    }
}
