package opca.proxy.structure;

import java.security.SecureRandom;
import java.util.concurrent.ConcurrentHashMap;

public class PositionMap {
    private final ConcurrentHashMap<Integer, PositionMapEntry> map;
    private final int numLeaves;
    private final SecureRandom rng;

    public PositionMap(int treeSize, int treeHeight) {
        this.map = new ConcurrentHashMap<>(treeSize);
        this.numLeaves = 1 << treeHeight;
        this.rng = new SecureRandom();

        for (int addr = 0; addr < treeSize; addr++) {
            map.put(addr, new PositionMapEntry(addr, rng.nextInt(numLeaves)));
        }
    }

    public PositionMapEntry get(int address) {
        return map.get(address);
    }

    public synchronized void incrementCount(int address) {
        PositionMapEntry entry = requireEntry(address);
        entry.getCount().incrementAndGet();
    }

    public void reassignPath(int address) {
        PositionMapEntry entry = requireEntry(address);
        entry.setPathId(rng.nextInt(numLeaves));
        entry.getCount().set(0);
    }

    private PositionMapEntry requireEntry(int address) {
        PositionMapEntry entry = map.get(address);
        if (entry == null) {
            throw new IllegalArgumentException("Invalid address=" + address + ", range is [0, " + (map.size() - 1) + "]");
        }
        return entry;
    }
}
