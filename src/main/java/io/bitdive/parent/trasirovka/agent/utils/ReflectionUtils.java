package io.bitdive.parent.trasirovka.agent.utils;


import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.serializer.SerializeFilter;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.alibaba.fastjson.serializer.ValueFilter;
import io.bitdive.parent.trasirovka.agent.utils.fast_json_custom.CustomSerializeConfig;
import io.bitdive.parent.trasirovka.agent.utils.fast_json_custom.CustomValueFilter;
import io.bitdive.parent.trasirovka.agent.utils.fast_json_custom.UniversalPropertyFilter;

public class ReflectionUtils {
    public static String objectToString(Object obj) {
        try {
            CustomSerializeConfig config = new CustomSerializeConfig();
            ValueFilter valueFilter = new CustomValueFilter();
            UniversalPropertyFilter universalPropertyFilter = new UniversalPropertyFilter();
            SerializeFilter[] filters = new SerializeFilter[]{valueFilter, universalPropertyFilter};

            return JSON.toJSONString(obj, config, filters, SerializerFeature.DisableCircularReferenceDetect);
        } catch (Exception e) {
            return "Error converting data";
        }
    }
}
