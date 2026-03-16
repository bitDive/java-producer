package io.bitdive.parent.trasirovka.agent.utils.objectMaperConfig;

public interface NestedValueSerializer {
    /**
     * Возвращает валидный JSON-фрагмент для value
     * или null, если сериализация не удалась.
     */
    String trySerialize(Object value);
}