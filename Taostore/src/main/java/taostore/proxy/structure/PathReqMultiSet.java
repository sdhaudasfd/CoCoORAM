package taostore.proxy.structure;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class PathReqMultiSet {
    private final ConcurrentHashMap<Integer, AtomicInteger> counts = new ConcurrentHashMap<>();

    public void add(int pathId) {
        counts.compute(pathId, (k, v) -> {
            if (v == null) {
                return new AtomicInteger(1);
            }
            v.incrementAndGet();
            return v;
        });
    }

    public void remove(int pathId) {
        counts.computeIfPresent(pathId, (k, v) -> v.decrementAndGet() <= 0 ? null : v);
    }

    public boolean contains(int pathId) {
        AtomicInteger c = counts.get(pathId);
        return c != null && c.get() > 0;
    }

    public Set<Integer> snapshotPathIds() {
        Set<Integer> active = new HashSet<>();
        for (Integer pathId : counts.keySet()) {
            if (contains(pathId)) {
                active.add(pathId);
            }
        }
        return active;
    }
}
