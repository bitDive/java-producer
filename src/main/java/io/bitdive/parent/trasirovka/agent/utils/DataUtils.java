package io.bitdive.parent.trasirovka.agent.utils;


import com.thoughtworks.paranamer.BytecodeReadingParanamer;
import io.bitdive.parent.anotations.NotMonitoringParamsClass;
import io.bitdive.parent.dto.ParamMethodDto;
import io.bitdive.parent.parserConfig.YamlParserConfig;
import org.apache.commons.lang3.ObjectUtils;

import java.lang.reflect.Method;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static io.bitdive.parent.trasirovka.agent.utils.ReflectionUtils.SENSITIVE_KEYWORDS;

public class DataUtils {

    public static final Set<String> SENSITIVE_KEYWORDS = new HashSet<>(Arrays.asList(
            "password", "pass", "secret", "token", "key", "apikey", "auth", "credential"
    ));

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

    public static List<ParamMethodDto> paramConvert(Object[] objects, Method method) {
        List<String> namesParam = new ArrayList<>();
        try {
            BytecodeReadingParanamer paranamer = new BytecodeReadingParanamer();
            namesParam.addAll(Arrays.asList(paranamer.lookupParameterNames(method)));
        } catch (Exception e) {

        }
        if (YamlParserConfig.getProfilingConfig().getMonitoring().getMonitoringArgumentMethod()) {
            return DataUtils.paramConvertToMess(objects, namesParam);
        }
        return new ArrayList<>();
    }



    public static Object methodReturnConvert(Object val){
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

    public static List<ParamMethodDto> paramConvertToMess(Object[] objects, List<String> namesParam) {
        ArrayList<ParamMethodDto> bufRet = new ArrayList<>();
        int index = 0;
        for (Object object : objects) {
            if (object != null) {
                Class<?> objectClass = object.getClass();
                NotMonitoringParamsClass notMonitoringClass = objectClass.getAnnotation(NotMonitoringParamsClass.class);
                Object bufVal;
                if (notMonitoringClass != null) {
                    bufVal = notMonitoringClass.value();
                } else {
                    if (objectClass.getName().contains("java.util.stream.")) {
                        bufVal = "[stream]";
                    } else {
                        bufVal = object;
                    }
                }

                String nameParam = namesParam.isEmpty() ? "" : namesParam.get(index);

                if (ObjectUtils.isNotEmpty(nameParam)) {
                    boolean isMaskField = false;
                    for (String litresMask : SENSITIVE_KEYWORDS) {
                        if (nameParam.toLowerCase().contains(litresMask) || litresMask.toLowerCase().contains(nameParam)) {
                            isMaskField = true;
                            break;
                        }
                    }
                    if (isMaskField)
                        bufRet.add(new ParamMethodDto(index, objectClass.getName(), "******"));
                    else
                        bufRet.add(new ParamMethodDto(index, objectClass.getName(), bufVal));
                } else {
                    bufRet.add(new ParamMethodDto(index, objectClass.getName(), bufVal));
                }


            } else {
                bufRet.add(new ParamMethodDto(index, "null val", ""));
            }
            index++;
        }
        return bufRet;
    }
}
