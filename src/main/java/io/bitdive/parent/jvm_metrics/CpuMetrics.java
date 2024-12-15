package io.bitdive.parent.jvm_metrics;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.reflect.Method;

public class CpuMetrics {
    private static final OperatingSystemMXBean osBean;
    private static final Method getSystemCpuLoadMethod;
    private static final Method getProcessCpuLoadMethod;
    private static final Method getTotalPhysicalMemorySizeMethod;
    private static final Method getFreePhysicalMemorySizeMethod;

    static {
        osBean = ManagementFactory.getOperatingSystemMXBean();
        try {
            Class<?> sunOsBeanClass = Class.forName("com.sun.management.OperatingSystemMXBean");
            if (sunOsBeanClass.isInstance(osBean)) {
                getSystemCpuLoadMethod = sunOsBeanClass.getMethod("getSystemCpuLoad");
                getProcessCpuLoadMethod = sunOsBeanClass.getMethod("getProcessCpuLoad");
                getTotalPhysicalMemorySizeMethod = sunOsBeanClass.getMethod("getTotalPhysicalMemorySize");
                getFreePhysicalMemorySizeMethod = sunOsBeanClass.getMethod("getFreePhysicalMemorySize");
            } else {
                throw new UnsupportedOperationException("Расширенный OperatingSystemMXBean не поддерживается в вашей JVM.");
            }
        } catch (Exception e) {
            throw new UnsupportedOperationException("Не удалось получить доступ к расширенным методам OperatingSystemMXBean.", e);
        }
    }

    public static double getSystemCpuLoad() {
        try {
            double load = (double) getSystemCpuLoadMethod.invoke(osBean);
            return (load < 0 ? 0 : load * 100);
        } catch (Exception e) {
            throw new RuntimeException("Ошибка при получении системной загрузки CPU.", e);
        }
    }

    public static double getProcessCpuLoad() {
        try {
            double load = (double) getProcessCpuLoadMethod.invoke(osBean);
            return (load < 0 ? 0 : load * 100);
        } catch (Exception e) {
            throw new RuntimeException("Ошибка при получении загрузки CPU процесса.", e);
        }
    }

    public static long getTotalPhysicalMemorySize() {
        try {
            return (long) getTotalPhysicalMemorySizeMethod.invoke(osBean);
        } catch (Exception e) {
            throw new RuntimeException("Ошибка при получении общего объема физической памяти.", e);
        }
    }

    public static long getFreePhysicalMemorySize() {
        try {
            return (long) getFreePhysicalMemorySizeMethod.invoke(osBean);
        } catch (Exception e) {
            throw new RuntimeException("Ошибка при получении свободной физической памяти.", e);
        }
    }
}