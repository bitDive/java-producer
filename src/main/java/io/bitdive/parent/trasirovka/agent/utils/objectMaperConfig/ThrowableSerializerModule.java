package io.bitdive.parent.trasirovka.agent.utils.objectMaperConfig;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.type.WritableTypeId;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.jsontype.TypeSerializer;
import com.fasterxml.jackson.databind.module.SimpleModule;

import java.io.IOException;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Set;

public class ThrowableSerializerModule extends SimpleModule {

    public ThrowableSerializerModule() {
        this(1, null);
    }

    public ThrowableSerializerModule(int maxCauseDepth) {
        this(maxCauseDepth, null);
    }

    public ThrowableSerializerModule(int maxCauseDepth, NestedValueSerializer nestedValueSerializer) {
        super("throwable-compact-serializer");
        addSerializer(Throwable.class, new CompactThrowableSerializer(maxCauseDepth, nestedValueSerializer));
    }

    private static class CompactThrowableSerializer extends JsonSerializer<Throwable> {

        private static final Set<String> EXCLUDED_FIELD_NAMES = new HashSet<>(Arrays.asList(
                "stackTrace",
                "suppressedExceptions",
                "backtrace",
                "depth",
                "UNASSIGNED_STACK",
                "SUPPRESSED_SENTINEL",
                "CAUSE_CAPTION",
                "SUPPRESSED_CAPTION",
                "serialVersionUID"
        ));

        private static final Set<String> EXCLUDED_GETTERS = new HashSet<>(Arrays.asList(
                "getClass",
                "getCause",
                "getStackTrace",
                "getSuppressed",
                "getMessage",
                "getLocalizedMessage"
        ));

        private final int maxCauseDepth;
        private final NestedValueSerializer nestedValueSerializer;

        public CompactThrowableSerializer(int maxCauseDepth, NestedValueSerializer nestedValueSerializer) {
            this.maxCauseDepth = Math.max(0, maxCauseDepth);
            this.nestedValueSerializer = nestedValueSerializer;
        }

        @Override
        public void serialize(Throwable throwable,
                              JsonGenerator gen,
                              SerializerProvider serializers) throws IOException {
            if (throwable == null) {
                gen.writeNull();
                return;
            }

            gen.writeStartObject();

            Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
            visited.add(throwable);

            writeThrowable(throwable, gen, serializers, 0, visited, false);

            gen.writeEndObject();
        }

        @Override
        public void serializeWithType(Throwable throwable,
                                      JsonGenerator gen,
                                      SerializerProvider serializers,
                                      TypeSerializer typeSer) throws IOException {
            if (throwable == null) {
                gen.writeNull();
                return;
            }

            WritableTypeId typeIdDef =
                    typeSer.writeTypePrefix(gen, typeSer.typeId(throwable, JsonToken.START_OBJECT));

            Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
            visited.add(throwable);

            writeThrowable(throwable, gen, serializers, 0, visited, true);

            typeSer.writeTypeSuffix(gen, typeIdDef);
        }

        private void writeThrowable(Throwable t,
                                    JsonGenerator gen,
                                    SerializerProvider serializers,
                                    int depth,
                                    Set<Throwable> visited,
                                    boolean typeIdAlreadyWritten) throws IOException {

            if (!typeIdAlreadyWritten) {
                gen.writeStringField("@class", t.getClass().getName());
            }

            if (t.getMessage() != null) {
                gen.writeStringField("message", truncate(t.getMessage(), 500));
            }

            Set<String> written = new HashSet<>();
            written.add("@class");
            written.add("message");

            serializeNonJdkFields(t, gen, serializers, written);
            serializeSafeGetters(t, gen, serializers, written);

            Throwable cause = t.getCause();
            if (cause != null && cause != t) {
                gen.writeFieldName("cause");

                if (depth < maxCauseDepth && visited.add(cause)) {
                    gen.writeStartObject();
                    writeThrowable(cause, gen, serializers, depth + 1, visited, false);
                    gen.writeEndObject();
                } else {
                    String msg = cause.getMessage();
                    gen.writeString(cause.getClass().getName()
                            + (msg != null ? (": " + truncate(msg, 500)) : ""));
                }
            }
        }

        private void serializeNonJdkFields(Throwable throwable,
                                           JsonGenerator gen,
                                           SerializerProvider serializers,
                                           Set<String> written) throws IOException {

            for (Class<?> currentClass = throwable.getClass();
                 currentClass != null && Throwable.class.isAssignableFrom(currentClass);
                 currentClass = currentClass.getSuperclass()) {

                String cn = currentClass.getName();
                if (cn.startsWith("java.") || cn.startsWith("jdk.") || cn.startsWith("sun.")) {
                    break;
                }

                for (Field field : currentClass.getDeclaredFields()) {
                    String fieldName = field.getName();

                    if (EXCLUDED_FIELD_NAMES.contains(fieldName)
                            || Modifier.isStatic(field.getModifiers())
                            || Modifier.isTransient(field.getModifiers())
                            || field.isSynthetic()) {
                        continue;
                    }

                    if ("cause".equals(fieldName)) {
                        continue;
                    }

                    if (!written.add(fieldName)) {
                        continue;
                    }

                    Object value = tryReadFieldValue(field, throwable);
                    if (value == null) {
                        written.remove(fieldName);
                        continue;
                    }

                    gen.writeFieldName(fieldName);
                    serializeFieldValue(value, gen, serializers);
                }
            }
        }

        private void serializeSafeGetters(Throwable t,
                                          JsonGenerator gen,
                                          SerializerProvider serializers,
                                          Set<String> written) throws IOException {
            Method[] methods = t.getClass().getMethods();

            int emitted = 0;
            for (Method m : methods) {
                if (m.getParameterCount() != 0) continue;
                if (m.getReturnType() == void.class) continue;
                if (m.isSynthetic() || m.isBridge()) continue;

                String name = m.getName();
                if (EXCLUDED_GETTERS.contains(name)) continue;

                if (!(name.startsWith("get") && name.length() > 3)
                        && !(name.startsWith("is") && name.length() > 2)) {
                    continue;
                }

                Class<?> dc = m.getDeclaringClass();
                if (dc == Object.class) continue;

                String prop = getterToProp(name);
                if (isBlank8(prop)) continue;

                if (!written.add(prop)) continue;

                Object value;
                try {
                    value = m.invoke(t);
                } catch (Throwable ex) {
                    written.remove(prop);
                    continue;
                }

                if (value == null) {
                    written.remove(prop);
                    continue;
                }

                if ("cause".equals(prop) && value instanceof Throwable) {
                    written.remove(prop);
                    continue;
                }

                gen.writeFieldName(prop);
                serializeFieldValue(value, gen, serializers);

                emitted++;
                if (emitted >= 40) {
                    break;
                }
            }
        }

        private void serializeFieldValue(Object value,
                                         JsonGenerator gen,
                                         SerializerProvider serializers) throws IOException {
            if (value == null) {
                gen.writeNull();
                return;
            }

            if (value instanceof String) {
                gen.writeString(truncate((String) value, 500));
                return;
            }

            if (value instanceof Number || value instanceof Boolean || value instanceof Character) {
                gen.writeObject(value);
                return;
            }

            if (value instanceof Enum) {
                gen.writeString(((Enum<?>) value).name());
                return;
            }

            if (value instanceof byte[]) {
                gen.writeString("byte[" + ((byte[]) value).length + "]");
                return;
            }

            if (value instanceof Throwable) {
                Throwable nested = (Throwable) value;
                String msg = nested.getMessage();
                gen.writeString(nested.getClass().getName()
                        + (msg != null ? (": " + truncate(msg, 200)) : ""));
                return;
            }

            // Главная ветка:
            // отдаём сериализацию вложенного сложного значения
            // в твой уже настроенный mapper/сериализатор.
            if (nestedValueSerializer != null) {
                try {
                    String json = nestedValueSerializer.trySerialize(value);
                    if (json != null && !json.isEmpty()) {
                        gen.writeRawValue(json);
                        return;
                    }
                } catch (Throwable ignored) {
                    // упадём в fallback ниже
                }

                gen.writeString(safeToString(value, 500));
                return;
            }

            // fallback только если делегат вообще не передан
            try {
                serializers.defaultSerializeValue(value, gen);
            } catch (Throwable ignored) {
                gen.writeString(safeToString(value, 500));
            }
        }

        private static String safeToString(Object value, int max) {
            try {
                return truncate(String.valueOf(value), max);
            } catch (Throwable e) {
                return value == null ? "null" : value.getClass().getName();
            }
        }

        private static boolean isBlank8(String s) {
            if (s == null) return true;
            for (int i = 0; i < s.length(); i++) {
                if (!Character.isWhitespace(s.charAt(i))) return false;
            }
            return true;
        }

        private static String getterToProp(String getterName) {
            String s;
            if (getterName.startsWith("get") && getterName.length() > 3) {
                s = getterName.substring(3);
            } else if (getterName.startsWith("is") && getterName.length() > 2) {
                s = getterName.substring(2);
            } else {
                return null;
            }

            if (s.isEmpty()) return null;
            return Character.toLowerCase(s.charAt(0)) + s.substring(1);
        }

        private static Object tryReadFieldValue(Field field, Object target) {
            try {
                if (!isAccessible(field, target)) {
                    if (!tryMakeAccessible(field, target)) {
                        return null;
                    }
                }
                return field.get(target);
            } catch (Throwable e) {
                return null;
            }
        }

        private static boolean isAccessible(Field f, Object target) {
            try {
                Method canAccess = Field.class.getMethod("canAccess", Object.class);
                Object r = canAccess.invoke(f, target);
                return (r instanceof Boolean) && (Boolean) r;
            } catch (Throwable ignore) {
                try {
                    Method isAccessible = AccessibleObject.class.getMethod("isAccessible");
                    Object r = isAccessible.invoke(f);
                    return (r instanceof Boolean) && (Boolean) r;
                } catch (Throwable ignore2) {
                    return false;
                }
            }
        }

        private static boolean tryMakeAccessible(Field f, Object target) {
            try {
                try {
                    Method trySet = AccessibleObject.class.getMethod("trySetAccessible");
                    Object r = trySet.invoke(f);
                    if (r instanceof Boolean && (Boolean) r) {
                        return true;
                    }
                } catch (Throwable ignore) {
                    // Java 8 fallback below
                }

                f.setAccessible(true);
                return true;
            } catch (Throwable e) {
                return false;
            }
        }

        private static String truncate(String s, int max) {
            if (s == null) return null;
            return s.length() > max ? s.substring(0, max) + "... (truncated)" : s;
        }
    }
}