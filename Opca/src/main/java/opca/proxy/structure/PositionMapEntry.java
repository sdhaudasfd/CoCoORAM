package opca.proxy.structure;

import java.util.concurrent.atomic.AtomicInteger;

public class PositionMapEntry {
    private final int address;
    private volatile int pathId;
    private final AtomicInteger count;

    public PositionMapEntry(int address, int pathId) {
        this.address = address;
        this.pathId = pathId;
        this.count = new AtomicInteger(0);
    }

    public int getAddress() {
        return address;
    }

    public int getPathId() {
        return pathId;
    }

    public void setPathId(int pathId) {
        this.pathId = pathId;
    }

    public AtomicInteger getCount() {
        return count;
    }
}
