package io.bitdive.parent.trasirovka.agent.utils;


import io.bitdive.parent.anotations.NotMonitoringParamsClass;
import io.bitdive.parent.dto.ParamMethodDto;
import io.bitdive.parent.parserConfig.YamlParserConfig;

import java.util.ArrayList;
import java.util.List;

public class DataUtils {

    public static List<ParamMethodDto> paramConvert(Object[] objects){
        if (YamlParserConfig.getProfilingConfig().getMonitoring().getMonitoringArgumentMethod()) {
            return DataUtils.paramConvertToMess(objects);
        }
        return new ArrayList<>();
    }



    public static Object methodReturnConvert(Object val){
        if (YamlParserConfig.getProfilingConfig().getMonitoring().getMonitoringReturnMethod()) {
            return val;
        }
        return new Object();
    }

    public static List<ParamMethodDto> paramConvertToMess(Object[] objects) {
        ArrayList<ParamMethodDto> bufRet = new ArrayList<>();
        int index = 0;
        for (Object object : objects) {
            if (object != null) {
                NotMonitoringParamsClass notMonitoringClass = object.getClass().getAnnotation(NotMonitoringParamsClass.class);
                Object bufVal;
                if (notMonitoringClass != null) {
                    bufVal = notMonitoringClass.value();
                } else {
                    bufVal = object;
                }
                bufRet.add(new ParamMethodDto(index, object.getClass().getName(), bufVal));
            } else {
                bufRet.add(new ParamMethodDto(index, "null val", ""));
            }
            index++;
        }
        return bufRet;
    }
}
