package io.bitdive.parent.trasirovka.agent.utils.objectMaperConfig;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.jsontype.TypeSerializer;
import com.fasterxml.jackson.databind.module.SimpleModule;

import java.io.IOException;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;


public class ThrowableSerializerModule extends SimpleModule {

            public ThrowableSerializerModule() {
                this(1);
            }

            public ThrowableSerializerModule(int maxCauseDepth) {
                super("throwable-compact-serializer");
                addSerializer(Throwable.class, new CompactThrowableSerializer(maxCauseDepth));
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

                public CompactThrowableSerializer(int maxCauseDepth) {
                    this.maxCauseDepth = Math.max(0, maxCauseDepth);
                }

                @Override
                public void serialize(Throwable throwable, JsonGenerator gen, SerializerProvider serializers) throws IOException {
                    if (throwable == null) {
                        gen.writeNull();
                        return;
                    }
                    gen.writeStartObject();
                    Set<Throwable> visited = new HashSet<>();
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

                    // TypeSerializer записывает @class автоматически
                    typeSer.writeTypePrefix(gen, typeSer.typeId(throwable, JsonToken.START_OBJECT));
                    
                    Set<Throwable> visited = new HashSet<>();
                    visited.add(throwable);
                    writeThrowable(throwable, gen, serializers, 0, visited, true);
                    
                    typeSer.writeTypeSuffix(gen, typeSer.typeId(throwable, JsonToken.END_OBJECT));
                }

                private void writeThrowable(Throwable t,
                                            JsonGenerator gen,
                                            SerializerProvider serializers,
                                            int depth,
                                            Set<Throwable> visited,
                                            boolean hasTypeId) throws IOException {

                    // Записываем тип только если TypeSerializer не сделал это
                    if (!hasTypeId) {
                        gen.writeStringField("@class", t.getClass().getName());
                    }

                    if (t.getMessage() != null) {
                        gen.writeStringField("message", truncate(t.getMessage(), 500));
                    }

                    // 2) поля через reflection (только НЕ-JDK)
                    Set<String> written = new HashSet<>();
                    serializeNonJdkFields(t, gen, written);

                    // 3) добор через safe-getters (публичные no-arg get*/is*)
                    serializeSafeGetters(t, gen, written);

                    // 4) cause рекурсивно
                    Throwable cause = t.getCause();
                    if (cause != null && cause != t) {
                        gen.writeFieldName("cause");

                        if (depth < maxCauseDepth && visited.add(cause)) {
                            gen.writeStartObject();
                            writeThrowable(cause, gen, serializers, depth + 1, visited, false);
                            gen.writeEndObject();
                        } else {
                            String msg = cause.getMessage();
                            gen.writeString(cause.getClass().getName() + (msg != null ? (": " + truncate(msg, 500)) : ""));
                        }
                    }
                }

                private void serializeNonJdkFields(Throwable throwable, JsonGenerator gen, Set<String> written) throws IOException {
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
                                    || Modifier.isTransient(field.getModifiers())) {
                                continue;
                            }

                            if (!written.add(fieldName)) {
                                continue;
                            }

                            // cause всегда пишем через getCause()
                            if ("cause".equals(fieldName)) {
                                continue;
                            }

                            Object value = tryReadFieldValue(field, throwable);
                            if (value == null) {
                                // если поле не удалось — его может “добрать” getter
                                written.remove(fieldName); // разрешаем safe-getter записать такое же имя
                                continue;
                            }

                            gen.writeFieldName(fieldName);
                            serializeFieldValue(value, gen);
                        }
                    }
                }

                private void serializeSafeGetters(Throwable t, JsonGenerator gen, Set<String> written) throws IOException {
                    // Берем public методы: это безопаснее с точки зрения модулей
                    Method[] methods = t.getClass().getMethods();

                    int emitted = 0;
                    for (Method m : methods) {
                        if (m.getParameterCount() != 0) continue;
                        if (m.getReturnType() == void.class) continue;

                        String name = m.getName();
                        if (EXCLUDED_GETTERS.contains(name)) continue;

                        if (!(name.startsWith("get") && name.length() > 3) && !(name.startsWith("is") && name.length() > 2)) {
                            continue;
                        }

                        // отсекаем совсем “базу”
                        Class<?> dc = m.getDeclaringClass();
                        if (dc == Object.class) continue;

                        String prop = getterToProp(name);
                        if (isBlank8(prop)) continue;

                        // не перетираем уже записанные поля
                        if (!written.add(prop)) continue;

                        Object value;
                        try {
                            value = m.invoke(t);
                        } catch (Throwable ex) {
                            // getter мог бросить исключение — просто пропускаем
                            written.remove(prop);
                            continue;
                        }

                        if (value == null) {
                            written.remove(prop);
                            continue;
                        }

                        // не дублируем cause как значение
                        if ("cause".equals(prop) && value instanceof Throwable) {
                            written.remove(prop);
                            continue;
                        }

                        gen.writeFieldName(prop);
                        serializeFieldValue(value, gen);

                        // защита от слишком болтливых классов
                        emitted++;
                        if (emitted >= 40) break;
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

                // Java 8/11/17 совместимость: не используем canAccess/trySetAccessible напрямую
                private static boolean isAccessible(Field f, Object target) {
                    try {
                        // Java 9+: canAccess(Object)
                        Method canAccess = Field.class.getMethod("canAccess", Object.class);
                        Object r = canAccess.invoke(f, target);
                        return (r instanceof Boolean) && (Boolean) r;
                    } catch (Throwable ignore) {
                        // Java 8: isAccessible()
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
                        // Java 9+: trySetAccessible()
                        try {
                            Method trySet = AccessibleObject.class.getMethod("trySetAccessible");
                            Object r = trySet.invoke(f);
                            if (r instanceof Boolean && (Boolean) r) return true;
                        } catch (Throwable ignore) {
                        }

                        // fallback: setAccessible(true) (может упасть на модулях)
                        f.setAccessible(true);
                        return true;
                    } catch (Throwable e) {
                        return false;
                    }
                }

                private void serializeFieldValue(Object value, JsonGenerator gen) throws IOException {
                    if (value == null) {
                        gen.writeNull();
                    } else if (value instanceof String) {
                        gen.writeString(truncate((String) value, 500));
                    } else if (value instanceof Number || value instanceof Boolean) {
                        gen.writeObject(value);
                    } else if (value instanceof Enum) {
                        gen.writeString(((Enum<?>) value).name());
                    } else if (value instanceof byte[]) {
                        // чтобы не писать мегабайты бинаря (responseBody бывает byte[])
                        gen.writeString("byte[" + ((byte[]) value).length + "]");
                    } else {
                        String strValue = String.valueOf(value);
                        gen.writeString(truncate(strValue, 500));
                    }
                }

                private static String truncate(String s, int max) {
                    if (s == null) return null;
                    return s.length() > max ? s.substring(0, max) + "... (truncated)" : s;
                }
            }
        }
