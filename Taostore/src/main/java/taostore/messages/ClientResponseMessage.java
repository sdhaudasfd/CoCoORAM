package taostore.messages;

import oram.common.ORAMUtils;

public class ClientResponseMessage {
    private final long requestId;
    private final byte[] value;

    public ClientResponseMessage(long requestId, byte[] value) {
        this.requestId = requestId;
        this.value = value;
    }

    public long getRequestId() {
        return requestId;
    }

    public byte[] getValue() {
        return value;
    }

    public byte[] toBytes() {
        int valueLen = value == null ? -1 : value.length;
        int total = Long.BYTES + Integer.BYTES + (valueLen < 0 ? 0 : valueLen);
        byte[] out = new byte[total];
        int offset = 0;

        writeLong(out, offset, requestId);
        offset += Long.BYTES;

        ORAMUtils.serializeInteger(valueLen, out, offset);
        offset += Integer.BYTES;

        if (valueLen > 0) {
            System.arraycopy(value, 0, out, offset, valueLen);
        }
        return out;
    }

    public static ClientResponseMessage fromBytes(byte[] input) {
        int offset = 0;
        long requestId = readLong(input, offset);
        offset += Long.BYTES;

        int valueLen = ORAMUtils.deserializeInteger(input, offset);
        offset += Integer.BYTES;

        byte[] value = null;
        if (valueLen > 0) {
            value = new byte[valueLen];
            System.arraycopy(input, offset, value, 0, valueLen);
        }
        return new ClientResponseMessage(requestId, value);
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
