package io.bitdive.parent.trasirovka.agent.utils;

import java.util.Optional;

public class ReflectionUtils {
    public static String objectToString(Object obj) {
        try {
            if (obj == null) {
                return "";
            }
            String serialized = JsonSerializer.serialize(obj);
            return Optional.of(serialized)
                    .filter(s -> !s.isEmpty())
                    .filter(s -> !s.equals("null"))
                    .orElse("");
        } catch (Exception e) {
            return "Error converting data";
        }
    }
}
