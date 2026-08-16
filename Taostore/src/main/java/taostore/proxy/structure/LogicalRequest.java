package taostore.proxy.structure;

public class LogicalRequest {
    private final long requestId;
    private final byte opType;
    private final int blockAddress;
    private final byte[] newValue;
    private volatile boolean fakeRead;

    public LogicalRequest(long requestId, byte opType, int blockAddress, byte[] newValue) {
        this.requestId = requestId;
        this.opType = opType;
        this.blockAddress = blockAddress;
        this.newValue = newValue;
    }

    public long getRequestId() {
        return requestId;
    }

    public byte getOpType() {
        return opType;
    }

    public int getBlockAddress() {
        return blockAddress;
    }

    public byte[] getNewValue() {
        return newValue;
    }

    public boolean isFakeRead() {
        return fakeRead;
    }

    public void setFakeRead(boolean fakeRead) {
        this.fakeRead = fakeRead;
    }
}
