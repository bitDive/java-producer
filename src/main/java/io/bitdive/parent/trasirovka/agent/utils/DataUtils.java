package io.bitdive.parent.trasirovka.agent.utils;


import io.bitdive.parent.anotations.NotMonitoringParamsClass;
import io.bitdive.parent.dto.ParamMethodDto;
import io.bitdive.parent.parserConfig.YamlParserConfig;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class DataUtils {

    public static String getLocalDateTimeJackson() {
        LocalDateTime now = LocalDateTime.now();

       return String.format(
                "[ %d, %d, %d, %d, %d, %d, %d]",
                now.getYear(),
                now.getMonthValue(),
                now.getDayOfMonth(),
                now.getHour(),
                now.getMinute(),
                now.getSecond(),
                now.getNano()
        );

    }

    public static List<ParamMethodDto> paramConvert(Object[] objects){
        if (YamlParserConfig.getProfilingConfig().getMonitoring().getMonitoringArgumentMethod()) {
            return DataUtils.paramConvertToKafkaMess(objects);
        }
        return new ArrayList<>();
    }



    public static Object methodReturnConvert(Object val){
        if (YamlParserConfig.getProfilingConfig().getMonitoring().getMonitoringReturnMethod()) {
            return val;
        }
        return new Object();
    }

    public static List<ParamMethodDto> paramConvertToKafkaMess(Object[] objects) {
        ArrayList<ParamMethodDto> bufRet = new ArrayList<>();
        int index = 0;
        for (Object object : objects) {
            NotMonitoringParamsClass notMonitoringClass= object.getClass().getAnnotation(NotMonitoringParamsClass.class);
            Object bufVal;
            if (notMonitoringClass!=null) {
                bufVal=notMonitoringClass.value();
            }else {
                bufVal=object;
            }
            bufRet.add(new ParamMethodDto(index, object.getClass().getName(),bufVal ));
            index++;
        }
        return bufRet;
    }
}
