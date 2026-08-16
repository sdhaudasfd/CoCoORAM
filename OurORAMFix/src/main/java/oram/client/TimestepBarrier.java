package oram.client;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;

public class TimestepBarrier {
    private final int parties;
    private final ConcurrentHashMap<Integer, CyclicBarrier> barriers;

    public TimestepBarrier(int parties) {
        if (parties <= 0) {
            throw new IllegalArgumentException("parties must be positive");
        }
        this.parties = parties;
        this.barriers = new ConcurrentHashMap<>();
    }

    public void await(int timestep) throws Exception {
        CyclicBarrier barrier = barriers.computeIfAbsent(
                timestep,
                ignored -> new CyclicBarrier(parties, () -> barriers.remove(timestep))
        );
        barrier.await();
    }
}
