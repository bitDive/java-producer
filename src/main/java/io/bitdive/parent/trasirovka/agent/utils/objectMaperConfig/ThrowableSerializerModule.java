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
 * <p><b>Подход:</b> Сериализуем только ПОЛЯ классов исключений через рефлексию,
 * исключая лишние внутренние поля JVM (stackTrace, backtrace, suppressedExceptions).
 * 
 * <p><b>Что включается:</b>
 * <ul>
 *   <li>Все поля класса исключения (detailMessage, errorCode, sqlState и т.д.)</li>
 *   <li>Поле cause (с ограничением глубины вложенности)</li>
 *   <li>Type ID (@class) для идентификации типа</li>
 * </ul>
 * 
 * <p><b>Что исключается:</b>
 * <ul>
 *   <li>stackTrace - огромный массив, не нужен для мониторинга</li>
 *   <li>backtrace, depth - внутренние поля JVM</li>
 *   <li>suppressedExceptions - редко используется</li>
 *   <li>static и transient поля</li>
 * </ul>
 * 
 * <p><b>Пример использования:</b>
 * <pre>{@code
 * // По умолчанию (минимально, 1 уровень вложенности):
 * mapper.registerModule(new ThrowableSerializerModule());
 * 
 * // С глубокой цепочкой причин (3 уровня):
 * mapper.registerModule(new ThrowableSerializerModule(3));
 * }</pre>
 */
public class ThrowableSerializerModule extends SimpleModule {

    /**
     * Конструктор с параметрами по умолчанию:
     * - 1 уровень вложенности для cause
     * 
     * Сериализует все поля класса исключения (detailMessage, errorCode, sqlState и т.д.)
     */
    public ThrowableSerializerModule() {
        this(1);
    }

    /**
     * Конструктор с настраиваемой глубиной вложенности.
     *
     * @param maxCauseDepth максимальная глубина вложенности причин (cause)
     */
    public ThrowableSerializerModule(int maxCauseDepth) {
        super("throwable-compact-serializer");
        addSerializer(Throwable.class, new CompactThrowableSerializer(maxCauseDepth));
    }

    /**
     * Компактный сериализатор для Throwable.
     * Сериализует только поля класса исключения, исключая лишние внутренние поля.
     */
    private static class CompactThrowableSerializer extends JsonSerializer<Throwable> {

        // Поля Throwable, которые НЕ нужно сериализовать (лишние/внутренние)
        private static final Set<String> EXCLUDED_FIELD_NAMES = new HashSet<>(Arrays.asList(
                "stackTrace",              // Огромный массив, не нужен
                "suppressedExceptions",    // Редко используется
                "backtrace",               // Внутреннее поле JVM
                "depth",                   // Внутреннее поле JVM
                "UNASSIGNED_STACK",        // Константа
                "SUPPRESSED_SENTINEL",     // Константа
                "CAUSE_CAPTION",           // Константа
                "SUPPRESSED_CAPTION",      // Константа
                "serialVersionUID"         // Служебное
        ));

        private final int maxCauseDepth;

        public CompactThrowableSerializer(int maxCauseDepth) {
            this.maxCauseDepth = maxCauseDepth;
        }

        @Override
        public void serialize(Throwable throwable, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            if (throwable == null) {
                gen.writeNull();
                return;
            }

            gen.writeStartObject();
            serializeFields(throwable, gen, 0);
            gen.writeEndObject();
        }

        @Override
        public void serializeWithType(Throwable throwable, JsonGenerator gen, SerializerProvider serializers, TypeSerializer typeSer) throws IOException {
            if (throwable == null) {
                gen.writeNull();
                return;
            }

            // TypeSerializer создает объект и записывает @class
            typeSer.writeTypePrefix(gen, 
                typeSer.typeId(throwable, JsonToken.START_OBJECT));
            
            // Сериализуем поля класса
            serializeFields(throwable, gen, 0);
            
            // Закрываем объект
            typeSer.writeTypeSuffix(gen, 
                typeSer.typeId(throwable, JsonToken.END_OBJECT));
        }

        /**
         * Сериализует поля класса исключения через рефлексию.
         * Исключает лишние поля (stackTrace, backtrace и т.д.)
         */
        private void serializeFields(Throwable throwable, JsonGenerator gen, int depth) throws IOException {
            // Проходим по всей иерархии классов до Throwable
            for (Class<?> currentClass = throwable.getClass(); 
                 currentClass != null && Throwable.class.isAssignableFrom(currentClass); 
                 currentClass = currentClass.getSuperclass()) {
                
                Field[] fields = currentClass.getDeclaredFields();
                
                for (Field field : fields) {
                    String fieldName = field.getName();
                    
                    // Пропускаем исключенные, static, transient поля
                    if (EXCLUDED_FIELD_NAMES.contains(fieldName) 
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

                        // Сериализуем поле
                        gen.writeFieldName(fieldName);
                        
                        // Особая обработка для поля cause (с ограничением глубины)
                        if ("cause".equals(fieldName) && value instanceof Throwable) {
                            Throwable cause = (Throwable) value;
                            if (cause != throwable && depth < maxCauseDepth) {
                                // Рекурсивно сериализуем cause с Type ID
                                gen.writeStartObject();
                                serializeFields(cause, gen, depth + 1);
                                gen.writeEndObject();
                            } else if (depth >= maxCauseDepth) {
                                // Достигнут лимит глубины - только строка
                                gen.writeString(cause.getClass().getName() + ": " + cause.getMessage());
                            } else {
                                gen.writeNull();
                            }
                        } else {
                            // Обычное поле - сериализуем с ограничениями
                            serializeFieldValue(value, gen);
                        }
                        
                    } catch (Exception e) {
                        // Игнорируем ошибки доступа к полям
                    }
                }
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
