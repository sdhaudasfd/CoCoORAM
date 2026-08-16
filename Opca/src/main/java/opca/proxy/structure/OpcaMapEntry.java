package opca.proxy.structure;

public class OpcaMapEntry {
    private final int bufIndex;
    private final long timestamp;

    public OpcaMapEntry(int bufIndex, long timestamp) {
        this.bufIndex = bufIndex;
        this.timestamp = timestamp;
    }

    public int getBufIndex() {
        return bufIndex;
    }

    public long getTimestamp() {
        return timestamp;
    }
}
