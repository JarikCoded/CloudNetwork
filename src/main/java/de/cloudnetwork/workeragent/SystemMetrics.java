package de.cloudnetwork.workeragent;

import java.lang.management.ManagementFactory;

public final class SystemMetrics {
    private SystemMetrics() {
    }

    public static double getCpuUsagePercent() {
        java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
        if (bean instanceof com.sun.management.OperatingSystemMXBean extendedBean) {
            double cpuLoad = extendedBean.getSystemCpuLoad();
            if (cpuLoad >= 0.0D) {
                return roundToTwoDecimals(cpuLoad * 100.0D);
            }
        }

        double loadAverage = bean.getSystemLoadAverage();
        int processors = Math.max(bean.getAvailableProcessors(), 1);
        if (loadAverage < 0.0D) {
            return 0.0D;
        }
        double estimated = (loadAverage / processors) * 100.0D;
        return roundToTwoDecimals(Math.max(0.0D, Math.min(estimated, 100.0D)));
    }

    public static double getRamUsagePercent() {
        Runtime runtime = Runtime.getRuntime();
        double totalMemory = runtime.totalMemory();
        double freeMemory = runtime.freeMemory();
        if (totalMemory <= 0.0D) {
            return 0.0D;
        }
        double usedPercent = ((totalMemory - freeMemory) / totalMemory) * 100.0D;
        return roundToTwoDecimals(Math.max(0.0D, Math.min(usedPercent, 100.0D)));
    }

    private static double roundToTwoDecimals(double value) {
        return Math.round(value * 100.0D) / 100.0D;
    }
}
