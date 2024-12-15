package io.bitdive.parent.trasirovka.agent.utils;


import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.serializer.SerializeConfig;
import com.alibaba.fastjson.serializer.SerializeFilter;
import com.alibaba.fastjson.serializer.SerializerFeature;
import io.bitdive.parent.trasirovka.agent.utils.fast_json_custom.CustomSerializeConfig;
import io.bitdive.parent.trasirovka.agent.utils.fast_json_custom.LimitSizeValueFilter;
import io.bitdive.parent.trasirovka.agent.utils.fast_json_custom.UniversalPropertyFilter;

import java.util.Optional;

public class ReflectionUtils {
    private final static SerializeFilter[] filters;
    private final static CustomSerializeConfig config;

    static {
        config = new CustomSerializeConfig();
        filters = new SerializeFilter[]{new UniversalPropertyFilter(), new LimitSizeValueFilter()};
    }
    public static String objectToString(Object obj) {
        try {
            if (obj != null) {
                SerializeConfig.getGlobalInstance().put(obj.getClass(), config.getObjectWriter(obj.getClass()));
            }
            String paramAfterSeariles = JSON.toJSONString(obj, filters, SerializerFeature.DisableCircularReferenceDetect);
            return Optional.ofNullable(paramAfterSeariles)
                    .filter(s -> !s.isEmpty())
                    .filter(s -> !s.equals("null")).orElse("");
        } catch (Exception e) {
            return "Error converting data";
        }
    }
}
