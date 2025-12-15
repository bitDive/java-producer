package io.bitdive.parent.trasirovka.agent.utils;

import java.io.ByteArrayOutputStream;

public class RequestBodyCollector {

    private static final ThreadLocal<ByteArrayOutputStream> bodyBufferThreadLocal = ThreadLocal.withInitial(ByteArrayOutputStream::new);

    public static void reset() {
        ByteArrayOutputStream current = bodyBufferThreadLocal.get();
        if (current != null) {
            try {
                current.reset();
            } catch (Exception ignored) {
            }
        } else {
            bodyBufferThreadLocal.set(new ByteArrayOutputStream());
        }
    }

    public static void append(byte[] data, int offset, int length) {
        if (data == null || length <= 0) return;
        try {
            bodyBufferThreadLocal.get().write(data, offset, length);
        } catch (Exception ignored) {
        }
    }

    public static void append(byte singleByte) {
        try {
            bodyBufferThreadLocal.get().write(singleByte);
        } catch (Exception ignored) {
        }
    }

    public static byte[] getBytes() {
        try {
            return bodyBufferThreadLocal.get().toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * КРИТИЧНО: Очистка ThreadLocal для предотвращения утечек памяти.
     * Должен вызываться после завершения обработки запроса.
     */
    public static void cleanup() {
        bodyBufferThreadLocal.remove();
    }

    /**
     * Безопасная очистка - игнорирует исключения
     */
    public static void cleanupSafely() {
        try {
            bodyBufferThreadLocal.remove();
        } catch (Exception e) {
            // Игнорируем исключения при очистке
        }
    }
}


