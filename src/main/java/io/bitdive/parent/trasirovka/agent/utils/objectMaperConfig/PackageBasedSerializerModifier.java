package io.bitdive.parent.trasirovka.agent.utils.objectMaperConfig;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;

import java.io.IOException;
import java.util.Optional;

public class PackageBasedSerializerModifier extends BeanSerializerModifier {

    private final String[] excludedPackages;

    public PackageBasedSerializerModifier(String... excludedPackages) {
        this.excludedPackages = excludedPackages;
    }

    @Override
    public JsonSerializer<?> modifySerializer(SerializationConfig config,
                                              BeanDescription beanDesc,
                                              JsonSerializer<?> serializer) {
        String packageName =
                Optional.ofNullable(beanDesc.getBeanClass())
                        .map(Class::getPackage)
                        .map(Package::getName).orElse(null);

        if (packageName == null) {
            return serializer;
        }

        for (String excludedPackage : excludedPackages) {
            if (packageName.startsWith(excludedPackage)) {
                return new JsonSerializer<Object>() {
                    @Override
                    public void serialize(Object value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
                        gen.writeString("[excluded packages]");
                    }
                };
            }
        }
        return serializer;
    }
}
