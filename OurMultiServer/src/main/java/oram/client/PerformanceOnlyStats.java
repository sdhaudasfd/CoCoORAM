package oram.client;

import java.util.concurrent.atomic.AtomicLong;

public final class PerformanceOnlyStats {
    private static final AtomicLong evictionOverflowEvents = new AtomicLong();
    private static final AtomicLong missingBlockEvents = new AtomicLong();
    private static final AtomicLong missingLastRLEvents = new AtomicLong();

    private PerformanceOnlyStats() {
    }

    public static void reset() {
        evictionOverflowEvents.set(0L);
        missingBlockEvents.set(0L);
        missingLastRLEvents.set(0L);
    }

    public static void recordEvictionOverflow() {
        evictionOverflowEvents.incrementAndGet();
    }

    public static void recordMissingBlock() {
        missingBlockEvents.incrementAndGet();
    }

    public static void recordMissingLastRL() {
        missingLastRLEvents.incrementAndGet();
    }

    public static long getEvictionOverflowEvents() {
        return evictionOverflowEvents.get();
    }

    public static long getMissingBlockEvents() {
        return missingBlockEvents.get();
    }

    public static long getMissingLastRLEvents() {
        return missingLastRLEvents.get();
    }
}
