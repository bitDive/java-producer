package io.bitdive.parent.trasirovka.agent.utils.objectMaperConfig;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.BeanPropertyWriter;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class MaskingFilter extends BeanSerializerModifier {
    private final Set<String> maskedFields;

    public MaskingFilter(Set<String> maskedFields) {
        this.maskedFields = maskedFields;
    }

    @Override
    public List<BeanPropertyWriter> changeProperties(SerializationConfig config,
                                                     BeanDescription beanDesc,
                                                     List<BeanPropertyWriter> beanProperties) {
        List<BeanPropertyWriter> out = new ArrayList<>(beanProperties.size());
        for (BeanPropertyWriter w : beanProperties) {
            if (maskedFields.contains(w.getName())) {
                out.add(new MaskingWriter(w));
            } else {
                out.add(w);
            }
        }
        return out;
    }

    static final class MaskingWriter extends BeanPropertyWriter {
        MaskingWriter(BeanPropertyWriter base) {
            super(base);
        }

        @Override
        public void serializeAsField(Object bean, JsonGenerator gen, SerializerProvider prov) throws Exception {
            gen.writeFieldName(getName());
            gen.writeString("****");
        }

        @Override
        public void serializeAsElement(Object bean, JsonGenerator gen, SerializerProvider prov) throws Exception {
            gen.writeString("****");
        }
    }
}

