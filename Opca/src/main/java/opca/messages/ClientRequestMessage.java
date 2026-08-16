package opca.messages;

import oram.common.ORAMUtils;

public class ClientRequestMessage {
    public static final byte READ = 0;
    public static final byte WRITE = 1;

    private final long requestId;
    private final byte opType;
    private final int blockAddress;
    private final byte[] data;

    public ClientRequestMessage(long requestId, byte opType, int blockAddress, byte[] data) {
        this.requestId = requestId;
        this.opType = opType;
        this.blockAddress = blockAddress;
        this.data = data;
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

    public byte[] getData() {
        return data;
    }

    public byte[] toBytes() {
        int dataLen = data == null ? -1 : data.length;
        int total = Long.BYTES + 1 + Integer.BYTES + Integer.BYTES + (dataLen < 0 ? 0 : dataLen);
        byte[] out = new byte[total];
        int offset = 0;

        writeLong(out, offset, requestId);
        offset += Long.BYTES;

        out[offset++] = opType;

        ORAMUtils.serializeInteger(blockAddress, out, offset);
        offset += Integer.BYTES;

        ORAMUtils.serializeInteger(dataLen, out, offset);
        offset += Integer.BYTES;

        if (dataLen > 0) {
            System.arraycopy(data, 0, out, offset, dataLen);
        }
        return out;
    }

    public static ClientRequestMessage fromBytes(byte[] input) {
        int offset = 0;
        long requestId = readLong(input, offset);
        offset += Long.BYTES;

        byte opType = input[offset++];

        int blockAddress = ORAMUtils.deserializeInteger(input, offset);
        offset += Integer.BYTES;

        int dataLen = ORAMUtils.deserializeInteger(input, offset);
        offset += Integer.BYTES;

        byte[] data = null;
        if (dataLen > 0) {
            data = new byte[dataLen];
            System.arraycopy(input, offset, data, 0, dataLen);
        }

        return new ClientRequestMessage(requestId, opType, blockAddress, data);
    }

    private static void writeLong(byte[] out, int startOffset, long value) {
        for (int i = 7; i >= 0; i--) {
            out[startOffset + i] = (byte) (value & 0xFFL);
            value >>>= 8;
        }
    }

    private static long readLong(byte[] input, int startOffset) {
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value <<= 8;
            value |= Byte.toUnsignedLong(input[startOffset + i]);
        }
        return value;
    }
}
