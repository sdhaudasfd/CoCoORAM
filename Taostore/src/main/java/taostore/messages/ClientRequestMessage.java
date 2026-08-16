package taostore.messages;

import oram.common.ORAMUtils;

public class ClientRequestMessage {
    public static final byte READ = 0;
    public static final byte WRITE = 1;

    private final long requestId;
    private final byte opType;
    private final int blockAddress;
    private final byte[] newValue;

    public ClientRequestMessage(long requestId, byte opType, int blockAddress, byte[] newValue) {
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

    public byte[] toBytes() {
        int valueLen = newValue == null ? -1 : newValue.length;
        int total = Long.BYTES + 1 + Integer.BYTES + Integer.BYTES + (valueLen < 0 ? 0 : valueLen);
        byte[] out = new byte[total];
        int offset = 0;

        writeLong(out, offset, requestId);
        offset += Long.BYTES;

        out[offset++] = opType;

        ORAMUtils.serializeInteger(blockAddress, out, offset);
        offset += Integer.BYTES;

        ORAMUtils.serializeInteger(valueLen, out, offset);
        offset += Integer.BYTES;

        if (valueLen > 0) {
            System.arraycopy(newValue, 0, out, offset, valueLen);
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

        int valueLen = ORAMUtils.deserializeInteger(input, offset);
        offset += Integer.BYTES;

        byte[] newValue = null;
        if (valueLen > 0) {
            newValue = new byte[valueLen];
            System.arraycopy(input, offset, newValue, 0, valueLen);
        }

        return new ClientRequestMessage(requestId, opType, blockAddress, newValue);
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
