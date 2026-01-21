package io.bitdive.parent.trasirovka.agent.utils.objectMaperConfig;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.BeanPropertyWriter;
import com.fasterxml.jackson.databind.ser.BeanSerializerBuilder;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Serializer modifier that safely handles serialVersionUID field serialization.
 * If the field is accessible, it will be serialized. If access fails (e.g., due to
 * Java 9+ module restrictions), it will be silently skipped without throwing an exception.
 * This prevents InaccessibleObjectException when trying to access serialVersionUID
 * in Java 9+ modules (e.g., java.util.UUID, java.time.LocalDate, etc.).
 */
public class SerialVersionUidExclusionModifier extends BeanSerializerModifier {

    @Override
    public BeanSerializerBuilder updateBuilder(SerializationConfig config,
                                               BeanDescription beanDesc,
                                               BeanSerializerBuilder builder) {
        List<BeanPropertyWriter> properties = builder.getProperties();
        if (properties != null && !properties.isEmpty()) {
            List<BeanPropertyWriter> processed = new ArrayList<>();
            for (BeanPropertyWriter writer : properties) {
                // Wrap serialVersionUID field with safe handler
                if ("serialVersionUID".equals(writer.getName())) {
                    processed.add(new SafeSerialVersionUidWriter(writer));
                } else {
                    processed.add(writer);
                }
            }
            builder.setProperties(processed);
        }
        return builder;
    }

    /**
     * Safe wrapper for serialVersionUID BeanPropertyWriter that handles access errors gracefully.
     */
    private static class SafeSerialVersionUidWriter extends BeanPropertyWriter {
        private final BeanPropertyWriter delegate;

        SafeSerialVersionUidWriter(BeanPropertyWriter delegate) {
            super(delegate);
            this.delegate = delegate;
        }

        @Override
        public void serializeAsField(Object bean, JsonGenerator gen, SerializerProvider prov) throws Exception {
            try {
                // Try to serialize the field normally
                delegate.serializeAsField(bean, gen, prov);
            } catch (Exception e) {
                // Check if this is an access-related exception (Java 9+ module restrictions)
                if (isAccessException(e)) {
                    // Silently skip - field is not accessible due to module restrictions
                    // Field value is preserved in the object, just not serialized
                    return;
                }
                // Re-throw if it's a different type of error
                throw e;
            }
        }

        @Override
        public void serializeAsElement(Object bean, JsonGenerator gen, SerializerProvider prov) throws Exception {
            try {
                delegate.serializeAsElement(bean, gen, prov);
            } catch (Exception e) {
                if (isAccessException(e)) {
                    // Write null instead of the field value if access fails
                    gen.writeNull();
                    return;
                }
                throw e;
            }
        }

        /**
         * Checks if the exception is related to field access restrictions in Java 9+ modules.
         */
        private boolean isAccessException(Exception e) {
            // Check exception type
            String exceptionClass = e.getClass().getName();
            if (exceptionClass.contains("InaccessibleObjectException") ||
                exceptionClass.contains("IllegalAccessException")) {
                return true;
            }

            // Check exception message
            String errorMsg = e.getMessage();
            if (errorMsg != null) {
                String lowerMsg = errorMsg.toLowerCase();
                return lowerMsg.contains("does not \"opens") ||
                       lowerMsg.contains("inaccessibleobjectexception") ||
                       lowerMsg.contains("illegalaccessexception") ||
                       (lowerMsg.contains("module") && lowerMsg.contains("accessible")) ||
                       (lowerMsg.contains("module") && lowerMsg.contains("opens"));
            }

            // Check cause
            Throwable cause = e.getCause();
            if (cause != null && cause != e) {
                String causeClass = cause.getClass().getName();
                if (causeClass.contains("InaccessibleObjectException") ||
                    causeClass.contains("IllegalAccessException")) {
                    return true;
                }
            }

            return false;
        }
    }
}
