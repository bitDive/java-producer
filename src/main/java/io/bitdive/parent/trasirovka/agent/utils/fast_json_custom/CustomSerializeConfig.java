package io.bitdive.parent.trasirovka.agent.utils.fast_json_custom;

import com.alibaba.fastjson.serializer.ObjectSerializer;
import com.alibaba.fastjson.serializer.SerializeConfig;
import io.bitdive.parent.anotations.NotMonitoringParamsClass;
import io.bitdive.parent.parserConfig.YamlParserConfig;

public class CustomSerializeConfig extends SerializeConfig {
    @Override
    public ObjectSerializer getObjectWriter(Class<?> clazz) {
        if (clazz.isAnnotationPresent(NotMonitoringParamsClass.class)) {
            return (serializer, object, fieldName, fieldType, features) -> {
                NotMonitoringParamsClass annotation = clazz.getAnnotation(NotMonitoringParamsClass.class);
                serializer.write(annotation.value());
            };
        }

        String[] excludedPackages = YamlParserConfig.getProfilingConfig()
                .getMonitoring().getSerialization().getExcludedPackages();
        for (String pkg : excludedPackages) {
            if (clazz.getName().startsWith(pkg)) {
                return (serializer, object, fieldName, fieldType, features) -> {
                    serializer.write(object.toString());
                };
            }
        }

        return super.getObjectWriter(clazz);
    }
}