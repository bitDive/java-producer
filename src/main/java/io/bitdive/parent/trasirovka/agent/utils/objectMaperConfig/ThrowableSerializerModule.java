package io.bitdive.parent.trasirovka.agent.utils.objectMaperConfig;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.jsontype.TypeSerializer;
import com.fasterxml.jackson.databind.module.SimpleModule;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Модуль для компактной сериализации исключений (Throwable).
 * 
 * <p><b>Проблема:</b> Полная сериализация исключений создает огромные JSON (~10-100 KB),
 * включая весь стектрейс, внутренние поля, циклические ссылки.
 * 
 * <p><b>Решение:</b> Сериализуем только критически важные данные:
 * <ul>
 *   <li><b>Тип исключения</b> - для идентификации типа ошибки</li>
 *   <li><b>Сообщение</b> - описание ошибки</li>
 *   <li><b>StackTrace (ограниченный)</b> - первые N элементов, чтобы понять МЕСТО возникновения ошибки</li>
 *   <li><b>Cause (ограниченный)</b> - цепочка причин для понимания корневой проблемы</li>
 * </ul>
 * 
 * <p><b>Примеры использования:</b>
 * <pre>{@code
 * // По умолчанию (оптимально для продакшена): 3 элемента стека
 * mapper.registerModule(new ThrowableSerializerModule());
 * 
 * // Минимальная сериализация (только тип и сообщение):
 * mapper.registerModule(ThrowableSerializerModule.minimal());
 * 
 * // Расширенная отладка (10 элементов стека):
 * mapper.registerModule(ThrowableSerializerModule.debug());
 * 
 * // Кастомная конфигурация:
 * mapper.registerModule(new ThrowableSerializerModule(5, 3, 2));
 * }</pre>
 */
public class ThrowableSerializerModule extends SimpleModule {

    /**
     * Конструктор с параметрами по умолчанию (минимальный для продакшена):
     * - 0 элементов стектрейса (не нужен для мониторинга)
     * - 1 уровень причины (только прямая cause)
     * - 0 подавленных исключений
     * 
     * Включает: тип, сообщение, additionalFields (errorCode, sqlState и т.д.)
     * Достаточно для unit-тестов и мониторинга.
     */
    public ThrowableSerializerModule() {
        this(0, 1, 0);
    }

    /**
     * Создает модуль для отладки с стектрейсом (первый элемент).
     * Полезно, когда нужно знать ТОЧНОЕ место ошибки.
     * 
     * @return модуль с одним элементом стека для определения места ошибки
     */
    public static ThrowableSerializerModule withStackTrace() {
        return new ThrowableSerializerModule(1, 1, 0);
    }

    /**
     * Создает модуль с расширенной сериализацией для глубокой отладки.
     * Включает больше деталей: 10 элементов стека, 3 уровня причин.
     * 
     * @return модуль с расширенными данными для детальной отладки
     */
    public static ThrowableSerializerModule debug() {
        return new ThrowableSerializerModule(10, 3, 3);
    }

    /**
     * Конструктор с настраиваемыми параметрами.
     *
     * @param maxStackTraceElements  максимальное количество элементов stackTrace (0 = не включать)
     * @param maxCauseDepth         максимальная глубина вложенности причин (0 = не включать)
     * @param maxSuppressedExceptions максимальное количество подавленных исключений (0 = не включать)
     */
    public ThrowableSerializerModule(int maxStackTraceElements, int maxCauseDepth, int maxSuppressedExceptions) {
        super("throwable-compact-serializer");
        addSerializer(Throwable.class, new CompactThrowableSerializer(
                maxStackTraceElements,
                maxCauseDepth,
                maxSuppressedExceptions
        ));
    }

    /**
     * Компактный сериализатор для Throwable.
     * Сериализует исключение в структуру с важными полями, но без избыточных данных.
     */
    private static class CompactThrowableSerializer extends JsonSerializer<Throwable> {

        // Стандартные поля Throwable, которые обрабатываются отдельно (не нужно сериализовать через рефлексию)
        private static final Set<String> EXCLUDED_FIELD_NAMES = new HashSet<>(Arrays.asList(
                "stackTrace", "suppressedExceptions", "cause", "detailMessage", 
                "backtrace", "depth", "UNASSIGNED_STACK", "SUPPRESSED_SENTINEL",
                "CAUSE_CAPTION", "SUPPRESSED_CAPTION", "serialVersionUID"
        ));

        private final int maxStackTraceElements;
        private final int maxCauseDepth;
        private final int maxSuppressedExceptions;

        public CompactThrowableSerializer(int maxStackTraceElements, int maxCauseDepth, int maxSuppressedExceptions) {
            this.maxStackTraceElements = maxStackTraceElements;
            this.maxCauseDepth = maxCauseDepth;
            this.maxSuppressedExceptions = maxSuppressedExceptions;
        }

        @Override
        public void serialize(Throwable throwable, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            if (throwable == null) {
                gen.writeNull();
                return;
            }

            serializeThrowable(throwable, gen, 0);
        }

        @Override
        public void serializeWithType(Throwable throwable, JsonGenerator gen, SerializerProvider serializers, TypeSerializer typeSer) throws IOException {
            if (throwable == null) {
                gen.writeNull();
                return;
            }

            // TypeSerializer обрабатывает создание объекта и запись @type
            // Записываем префикс типа (например, {"@type": "..."})
            typeSer.writeTypePrefix(gen, 
                typeSer.typeId(throwable, JsonToken.START_OBJECT));
            
            // Сериализуем тело исключения
            serializeThrowableBody(throwable, gen, 0);
            
            // Записываем суффикс типа
            typeSer.writeTypeSuffix(gen, 
                typeSer.typeId(throwable, JsonToken.END_OBJECT));
        }

        private void serializeThrowable(Throwable throwable, JsonGenerator gen, int depth) throws IOException {
            gen.writeStartObject();
            serializeThrowableBody(throwable, gen, depth);
            gen.writeEndObject();
        }

        /**
         * Сериализует тело исключения (без оборачивающего объекта).
         * Используется как в обычной сериализации, так и при Type ID handling.
         */
        private void serializeThrowableBody(Throwable throwable, JsonGenerator gen, int depth) throws IOException {
            // Тип исключения
            gen.writeStringField("exceptionType", throwable.getClass().getName());

            // Сообщение
            String message = throwable.getMessage();
            gen.writeStringField("message", message != null ? message : "");

            // Локализованное сообщение (если отличается)
            String localizedMessage = throwable.getLocalizedMessage();
            if (localizedMessage != null && !localizedMessage.equals(message)) {
                gen.writeStringField("localizedMessage", localizedMessage);
            }

            // Дополнительные поля исключения (через рефлексию)
            serializeAdditionalFields(throwable, gen);

            // StackTrace (ограниченный) - только если настроено включать
            if (maxStackTraceElements > 0) {
                StackTraceElement[] stackTrace = throwable.getStackTrace();
                if (stackTrace != null && stackTrace.length > 0) {
                    gen.writeArrayFieldStart("stackTrace");
                    
                    int limit = Math.min(stackTrace.length, maxStackTraceElements);
                    for (int i = 0; i < limit; i++) {
                        StackTraceElement element = stackTrace[i];
                        gen.writeStartObject();
                        gen.writeStringField("class", element.getClassName());
                        gen.writeStringField("method", element.getMethodName());
                        String fileName = element.getFileName();
                        if (fileName != null) {
                            gen.writeStringField("file", fileName);
                        }
                        gen.writeNumberField("line", element.getLineNumber());
                        gen.writeEndObject();
                    }
                    
                    // Если стектрейс был обрезан, добавляем информацию
                    if (stackTrace.length > maxStackTraceElements) {
                        gen.writeStartObject();
                        gen.writeStringField("info", "... " + (stackTrace.length - maxStackTraceElements) + " more elements");
                        gen.writeEndObject();
                    }
                    
                    gen.writeEndArray();
                }
            }

            // Причина (cause) - рекурсивно, но с ограничением глубины
            Throwable cause = throwable.getCause();
            if (cause != null && cause != throwable && depth < maxCauseDepth) {
                gen.writeFieldName("cause");
                serializeThrowable(cause, gen, depth + 1);
            } else if (depth >= maxCauseDepth && cause != null && cause != throwable) {
                gen.writeStringField("causeInfo", "Cause depth limit reached: " + cause.getClass().getName() + ": " + cause.getMessage());
            }

            // Подавленные исключения (suppressed) - только если настроено включать
            if (maxSuppressedExceptions > 0) {
                Throwable[] suppressed = throwable.getSuppressed();
                if (suppressed != null && suppressed.length > 0) {
                    gen.writeArrayFieldStart("suppressed");
                    
                    int suppressedLimit = Math.min(suppressed.length, maxSuppressedExceptions);
                    for (int i = 0; i < suppressedLimit; i++) {
                        serializeThrowable(suppressed[i], gen, depth + 1);
                    }
                    
                    if (suppressed.length > maxSuppressedExceptions) {
                        gen.writeStartObject();
                        gen.writeStringField("info", "... " + (suppressed.length - maxSuppressedExceptions) + " more suppressed");
                        gen.writeEndObject();
                    }
                    
                    gen.writeEndArray();
                }
            }
        }

        /**
         * Сериализует дополнительные поля исключения через рефлексию.
         * Включает только важные поля (не стандартные поля Throwable, не transient, не static).
         */
        private void serializeAdditionalFields(Throwable throwable, JsonGenerator gen) throws IOException {
            Class<?> clazz = throwable.getClass();
            
            // Собираем поля только из классов-наследников Throwable (не из самого Throwable)
            if (clazz == Throwable.class || clazz == Exception.class || clazz == RuntimeException.class || clazz == Error.class) {
                return; // Нет кастомных полей
            }

            boolean hasAdditionalFields = false;
            
            // Проходим по всем полям класса и его суперклассов (до Throwable)
            for (Class<?> currentClass = clazz; currentClass != null && currentClass != Throwable.class; currentClass = currentClass.getSuperclass()) {
                Field[] fields = currentClass.getDeclaredFields();
                
                for (Field field : fields) {
                    // Пропускаем исключенные, static, transient поля
                    if (EXCLUDED_FIELD_NAMES.contains(field.getName()) 
                            || Modifier.isStatic(field.getModifiers())
                            || Modifier.isTransient(field.getModifiers())) {
                        continue;
                    }

                    try {
                        field.setAccessible(true);
                        Object value = field.get(throwable);
                        
                        // Пропускаем null значения
                        if (value == null) {
                            continue;
                        }

                        // Первое дополнительное поле - открываем объект
                        if (!hasAdditionalFields) {
                            gen.writeObjectFieldStart("additionalFields");
                            hasAdditionalFields = true;
                        }

                        // Сериализуем значение поля (с ограничением для сложных объектов)
                        gen.writeFieldName(field.getName());
                        serializeFieldValue(value, gen);
                        
                    } catch (Exception e) {
                        // Игнорируем ошибки доступа к полям
                    }
                }
            }

            // Закрываем объект additionalFields, если были добавлены поля
            if (hasAdditionalFields) {
                gen.writeEndObject();
            }
        }

        /**
         * Сериализует значение поля с ограничениями (чтобы не раздуть JSON).
         */
        private void serializeFieldValue(Object value, JsonGenerator gen) throws IOException {
            if (value == null) {
                gen.writeNull();
            } else if (value instanceof String) {
                String str = (String) value;
                // Ограничиваем длину строк
                if (str.length() > 500) {
                    gen.writeString(str.substring(0, 500) + "... (truncated)");
                } else {
                    gen.writeString(str);
                }
            } else if (value instanceof Number || value instanceof Boolean) {
                gen.writeObject(value);
            } else if (value instanceof Enum) {
                gen.writeString(value.toString());
            } else {
                // Для сложных объектов - только toString (без глубокой сериализации)
                String strValue = value.toString();
                if (strValue.length() > 200) {
                    gen.writeString(strValue.substring(0, 200) + "...");
                } else {
                    gen.writeString(strValue);
                }
            }
        }
    }
}

