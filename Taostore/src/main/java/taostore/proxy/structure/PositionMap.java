package taostore.proxy.structure;

import java.security.SecureRandom;
import java.util.concurrent.locks.ReentrantLock;

public class PositionMap {
    private static final int STRIPES = 64;
    private final int[] map;
    private final int numLeaves;
    private final SecureRandom rng;
    private final ReentrantLock[] stripes;

    public PositionMap(int treeSize, int treeHeight) {
        this.map = new int[treeSize];
        this.numLeaves = 1 << treeHeight;
        this.rng = new SecureRandom();
        this.stripes = new ReentrantLock[STRIPES];
        for (int i = 0; i < STRIPES; i++) {
            stripes[i] = new ReentrantLock();
        }
        for (int i = 0; i < map.length; i++) {
            map[i] = rng.nextInt(numLeaves);
        }
    }

    private ReentrantLock lockFor(int blockAddress) {
        return stripes[blockAddress % STRIPES];
    }

    public int getAndRefresh(int blockAddress) {
        validateBlockAddress(blockAddress);
        ReentrantLock lock = lockFor(blockAddress);
        lock.lock();
        try {
            int oldPathId = map[blockAddress];
            map[blockAddress] = rng.nextInt(numLeaves);
            return oldPathId;
        } finally {
            lock.unlock();
        }
    }

    public int get(int blockAddress) {
        validateBlockAddress(blockAddress);
        ReentrantLock lock = lockFor(blockAddress);
        lock.lock();
        try {
            return map[blockAddress];
        } finally {
            lock.unlock();
        }
    }

    public void set(int blockAddress, int pathId) {
        validateBlockAddress(blockAddress);
        ReentrantLock lock = lockFor(blockAddress);
        lock.lock();
        try {
            map[blockAddress] = pathId;
        } finally {
            lock.unlock();
        }
    }

    private void validateBlockAddress(int blockAddress) {
        if (blockAddress < 0 || blockAddress >= map.length) {
            throw new IllegalArgumentException(
                    "Invalid blockAddress=" + blockAddress + ", expected range [0, " + (map.length - 1) + "]"
            );
        }
    }
}
