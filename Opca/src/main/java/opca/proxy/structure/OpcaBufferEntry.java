package opca.proxy.structure;

import java.util.Arrays;

public class OpcaBufferEntry {
    private final int address;
    private final byte[] data;
    private final long timestamp;
    private final int bufIndex;

    public OpcaBufferEntry(int address, byte[] data, long timestamp, int bufIndex) {
        this.address = address;
        this.data = data == null ? null : Arrays.copyOf(data, data.length);
        this.timestamp = timestamp;
        this.bufIndex = bufIndex;
    }

    public int getAddress() {
        return address;
    }

    public byte[] getData() {
        return data == null ? null : Arrays.copyOf(data, data.length);
    }

    public long getTimestamp() {
        return timestamp;
    }

    public int getBufIndex() {
        return bufIndex;
    }
}
