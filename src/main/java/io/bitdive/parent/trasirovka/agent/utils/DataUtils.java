package io.bitdive.parent.trasirovka.agent.utils;

import com.thoughtworks.paranamer.BytecodeReadingParanamer;
import io.bitdive.parent.anotations.NotMonitoringParamsClass;
import io.bitdive.parent.parserConfig.YamlParserConfig;
import org.apache.commons.lang3.ObjectUtils;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.time.temporal.TemporalAccessor;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class DataUtils {

    private static final ConcurrentHashMap<Method, ParamMeta> PARAM_NAMES_CACHE =
            new ConcurrentHashMap<Method, ParamMeta>();

    private static final BytecodeReadingParanamer PARAMETER = new BytecodeReadingParanamer();
    private static final String[] EMPTY_NAMES = new String[0];

    private static final ClassValue<Boolean> SIMPLE_LIKE_CACHE = new ClassValue<Boolean>() {
        @Override
        protected Boolean computeValue(Class<?> cls) {
            if (cls.isPrimitive() || cls.isEnum()) return Boolean.TRUE;

            if (CharSequence.class.isAssignableFrom(cls)) return Boolean.TRUE; // String, StringBuilder...
            if (Number.class.isAssignableFrom(cls)) return Boolean.TRUE;       // Integer, Long, BigDecimal...
            if (cls == Boolean.class || cls == Character.class) return Boolean.TRUE;

            if (Date.class.isAssignableFrom(cls)) return Boolean.TRUE;
            if (TemporalAccessor.class.isAssignableFrom(cls)) return Boolean.TRUE; // LocalDate, Instant...
            if (UUID.class.isAssignableFrom(cls)) return Boolean.TRUE;

            return Boolean.FALSE;
        }
    };

    private static final class ParamMeta {
        final String[] names;
        final boolean[] sensitiveByIndex;

        ParamMeta(String[] names, boolean[] sensitiveByIndex) {
            this.names = names;
            this.sensitiveByIndex = sensitiveByIndex;
        }

        boolean isSensitive(int idx) {
            return idx >= 0 && idx < sensitiveByIndex.length && sensitiveByIndex[idx];
        }

        String getName(int idx) {
            if (idx < 0 || idx >= names.length) return "";
            String n = names[idx];
            return n == null ? "" : n;
        }
    }

    private static ParamMeta buildParamMeta(Method m) {
        String[] names;
        try {
            names = PARAMETER.lookupParameterNames(m, false);
        } catch (Exception e) {
            names = EMPTY_NAMES;
        }

        boolean[] sens = new boolean[names.length];
        for (int i = 0; i < names.length; i++) {
            sens[i] = isSensitiveParamName(names[i]);
        }

        return new ParamMeta(names, sens);
    }

    /**
     * Fast check if className represents a file-related class.
     * Optimized to reduce string operations.
     */
    private static boolean isFileClass(String className) {
        if (className == null) return false;

        // MultipartFile (Spring)
        if (className.indexOf("MultipartFile") != -1) return true;

        // FileItem (Apache Commons FileUpload)
        if (className.indexOf("FileItem") != -1) return true;

        // Part (Servlet API) - be specific to avoid false positives like "Department"
        if (className.indexOf(".Part") != -1 || className.endsWith("Part")) {
            return className.contains("servlet") || className.contains("jakarta");
        }

        return false;
    }

    public static String getaNullThrowable(Throwable thrown) {
        if (thrown == null) {
            return "";
        }

        String message = Optional.ofNullable(thrown.getMessage())
                .map(msg -> msg.replace("\r", "").replace("\n", " "))
                .filter(s -> !s.equals("null"))
                .orElse("");

        StringBuilder sb = new StringBuilder();
        sb.append(getThrowableString(thrown));

        if (!message.isEmpty()) {
            sb.append(" Error: ").append(message);
        }
        return sb.toString();
    }

    private static String getThrowableString(Throwable thrown) {
        for (StackTraceElement elem : thrown.getStackTrace()) {
            String className = elem.getClassName();

            for (String pkg : YamlParserConfig.getProfilingConfig().getApplication().getPackedScanner()) {
                if (className.contains(pkg)) {
                    return elem.toString();
                }
            }
        }
        return "";
    }

    public static List<ParamMethod> paramConvert(Object[] objects, Method method) {

        if (!YamlParserConfig.getProfilingConfig().getMonitoring().getMonitoringArgumentMethod()) {
            return Collections.emptyList();
        }

        if (objects == null || objects.length == 0 || method == null) {
            return Collections.emptyList();
        }
        ParamMeta meta = PARAM_NAMES_CACHE.computeIfAbsent(method, DataUtils::buildParamMeta);
        return DataUtils.paramConvertToMess(objects, meta);
    }

    public static Object methodReturnConvert(Object val) {
        if (YamlParserConfig.getProfilingConfig().getMonitoring().getMonitoringReturnMethod()) {
            if (val == null) {
                return null;
            }
            Class<?> objectClass = val.getClass();
            if (objectClass.getName().contains("java.util.stream.")) {
                return "[stream]";
            }
            return val;
        }
        return null;
    }

    public static List<ParamMethod> paramConvertToMess(Object[] objects, List<String> namesParam) {
        String[] names = (namesParam == null || namesParam.isEmpty())
                ? EMPTY_NAMES
                : namesParam.toArray(new String[0]);

        boolean[] sens = new boolean[names.length];
        for (int i = 0; i < names.length; i++) {
            sens[i] = isSensitiveParamName(names[i]);
        }

        return paramConvertToMess(objects, new ParamMeta(names, sens));
    }

    /**
     * Быстрый путь: meta уже содержит precomputed sensitiveByIndex.
     */
    public static List<ParamMethod> paramConvertToMess(Object[] objects, ParamMeta meta) {
        ArrayList<ParamMethod> bufRet = new ArrayList<ParamMethod>(objects.length);

        for (int index = 0; index < objects.length; index++) {
            Object object = objects[index];

            if (object == null) {
                bufRet.add(new ParamMethod(index, "null val", ""));
                continue;
            }

            Class<?> objectClass = object.getClass();

            // 1) Если класс помечен как "не мониторить", отдаём заглушку и выходим
            NotMonitoringParamsClass notMonitoringClass = objectClass.getAnnotation(NotMonitoringParamsClass.class);
            if (notMonitoringClass != null) {
                bufRet.add(new ParamMethod(index, objectClass.getName(), notMonitoringClass.value()));
                continue;
            }

            // 2) Маскирование ТОЛЬКО для простых типов и контейнеров простых значений
            if (meta != null && meta.isSensitive(index) && isSimpleLikeOrContainerOfSimple(object)) {
                bufRet.add(new ParamMethod(index, objectClass.getName(), maskedPlaceholder(object)));
                continue;
            }

            // 3) Твоя текущая логика "спец-типов" (stream / file / file list)
            Object bufVal;
            String className = objectClass.getName();

            if (className.contains("java.util.stream.")) {
                bufVal = "[stream]";
            } else if (isFileClass(className)) {
                bufVal = objectClass.isArray() ? "[file array]" : "[file]";
            } else if (object instanceof Collection) {
                Collection<?> collection = (Collection<?>) object;
                if (!collection.isEmpty()) {
                    Object firstElement = collection.iterator().next();
                    if (firstElement != null && isFileClass(firstElement.getClass().getName())) {
                        bufVal = "[file list]";
                    } else {
                        bufVal = object;
                    }
                } else {
                    bufVal = object;
                }
            } else {
                bufVal = object;
            }

            bufRet.add(new ParamMethod(index, objectClass.getName(), bufVal));
        }

        return bufRet;
    }

    // -----------------------
    // Mask helpers
    // -----------------------

    private static boolean isSensitiveParamName(String nameParam) {
        if (!ObjectUtils.isNotEmpty(nameParam)) return false;

        // normalize: camelCase -> snake_case, lower
        String normalized = nameParam
                .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .toLowerCase(Locale.ROOT);

        // 1) точное попадание по токенам (минимум ложных срабатываний)
        String[] tokens = normalized.split("[^a-z0-9]+");
        for (String t : tokens) {
            if (!t.isEmpty() && YamlParserConfig.getProfilingConfig().getMonitoring().getSerialization().getSensitiveKeyWords().contains(t)) {
                return true;
            }
        }

        // 2) подстрока для случаев типа access_token / apiKey / refreshToken
        for (String kw : YamlParserConfig.getProfilingConfig().getMonitoring().getSerialization().getSensitiveKeyWords()) {
            if (kw.length() >= 4 && normalized.contains(kw)) {
                return true;
            }
        }

        return false;
    }

    private static boolean isSimpleLikeClass(Class<?> cls) {
        return SIMPLE_LIKE_CACHE.get(cls);
    }

    /**
     * true, если значение:
     * - simple-like (String/Number/Boolean/Date/Temporal/UUID/enum/primitive wrapper)
     * - или контейнер simple-like (array/collection/map по значениям)
     */
    private static boolean isSimpleLikeOrContainerOfSimple(Object value) {
        if (value == null) return true;

        Class<?> cls = value.getClass();
        if (isSimpleLikeClass(cls)) return true;

        // arrays
        if (cls.isArray()) {
            Class<?> comp = cls.getComponentType();
            if (comp.isPrimitive() || isSimpleLikeClass(comp)) return true;

            int len = Array.getLength(value);
            int limit = Math.min(len, 16); // не сканируем огромные массивы
            for (int i = 0; i < limit; i++) {
                Object el = Array.get(value, i);
                if (el != null) return isSimpleLikeClass(el.getClass());
            }
            return true; // пустой/все null -> считаем простым контейнером
        }

        // collections
        if (value instanceof Collection) {
            Collection<?> c = (Collection<?>) value;
            if (c.isEmpty()) return true;

            int seen = 0;
            for (Object el : c) {
                if (el == null) continue;
                if (!isSimpleLikeClass(el.getClass())) return false;
                if (++seen >= 16) break;
            }
            return true;
        }

        // maps (по значениям)
        if (value instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) value;
            if (m.isEmpty()) return true;

            int seen = 0;
            for (Object v : m.values()) {
                if (v == null) continue;
                if (!isSimpleLikeClass(v.getClass())) return false;
                if (++seen >= 16) break;
            }
            return true;
        }

        return false;
    }

    private static Object maskedPlaceholder(Object original) {
        if (original == null) return "******";

        Class<?> cls = original.getClass();
        if (cls.isArray()) {
            return "[masked array len=" + Array.getLength(original) + "]";
        }
        if (original instanceof Collection) {
            Collection<?> c = (Collection<?>) original;
            return "[masked collection size=" + c.size() + "]";
        }
        if (original instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) original;
            return "[masked map size=" + m.size() + "]";
        }
        return "******";
    }
}
