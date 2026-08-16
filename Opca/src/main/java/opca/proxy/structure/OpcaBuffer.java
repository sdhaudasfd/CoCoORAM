package opca.proxy.structure;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class OpcaBuffer {
    private final ConcurrentHashMap<Integer, OpcaBufferEntry> entries;
    private final AtomicInteger nextIndex;

    public OpcaBuffer() {
        this.entries = new ConcurrentHashMap<>();
        this.nextIndex = new AtomicInteger(0);
    }

    public int write(int address, byte[] data, long timestamp) {
        int index = nextIndex.getAndIncrement();
        entries.put(index, new OpcaBufferEntry(address, data, timestamp, index));
        return index;
    }

    public OpcaBufferEntry read(int bufIndex) {
        return entries.get(bufIndex);
    }

    public void clear() {
        entries.clear();
        nextIndex.set(0);
    }

    public int size() {
        return entries.size();
    }

    public Map<Integer, OpcaBufferEntry> snapshot() {
        return new ConcurrentHashMap<>(entries);
    }
}
