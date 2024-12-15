package io.bitdive.parent.jvm_metrics;


import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;
import java.nio.file.FileStore;
import java.nio.file.FileSystems;
import java.util.ArrayList;
import java.util.List;

public class JvmMetricsService {

    private static final MemoryMXBean memoryMXBean;
    private static final ThreadMXBean threadMXBean;
    private static final Runtime runtime;

    static {
        memoryMXBean = ManagementFactory.getMemoryMXBean();
        threadMXBean = ManagementFactory.getThreadMXBean();
        runtime = Runtime.getRuntime();
    }

    // Метрики памяти
    public static long getUsedHeapMemory() {
        return memoryMXBean.getHeapMemoryUsage().getUsed();
    }

    public static long getNonHeapMemoryUsed() {
        return memoryMXBean.getNonHeapMemoryUsage().getUsed();
    }

    // Метрики потоков
    public static int getThreadCount() {
        return threadMXBean.getThreadCount();
    }

    public static long getDaemonThreadCount() {
        return threadMXBean.getDaemonThreadCount();
    }

    // Метрики Runtime
    public static long getTotalMemory() {
        return runtime.totalMemory();
    }

    public static long getFreeMemory() {
        return runtime.freeMemory();
    }

    public static long getMaxMemory() {
        return runtime.maxMemory();
    }

    public static int getAvailableProcessors() {
        return runtime.availableProcessors();
    }

    // Метрики CPU
    public static double getSystemCpuLoad() {
        return CpuMetrics.getSystemCpuLoad();
    }

    public static double getProcessCpuLoad() {
        return CpuMetrics.getProcessCpuLoad();
    }

    public static long getTotalSwapSpace() {
        return CpuMetrics.getTotalPhysicalMemorySize();
    }

    public static long getFreeSwapSpace() {
        return CpuMetrics.getFreePhysicalMemorySize();
    }

    // Метрики диска
    public static List<DiskMetrics> getDiskMetrics() {
        List<DiskMetrics> disks = new ArrayList<>();
        for (FileStore store : FileSystems.getDefault().getFileStores()) {
            DiskMetrics diskMetrics = new DiskMetrics();
            try {
                diskMetrics.setName(store.name());
                diskMetrics.setType(store.type());
                diskMetrics.setTotalSpace(store.getTotalSpace());
                diskMetrics.setUsableSpace(store.getUsableSpace());
                diskMetrics.setFreeSpace(store.getUnallocatedSpace());
            } catch (IOException e) {
                System.err.println("Error load disk metrics");
            }
            disks.add(diskMetrics);
        }
        return disks;
    }
}
