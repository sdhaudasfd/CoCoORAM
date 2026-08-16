package taostore.messages;

import oram.common.ORAMUtils;

public class ReadPathRequestMessage {
    private final long correlationId;
    private final int pathId;

    public ReadPathRequestMessage(long correlationId, int pathId) {
        this.correlationId = correlationId;
        this.pathId = pathId;
    }

    public long getCorrelationId() {
        return correlationId;
    }

    public int getPathId() {
        return pathId;
    }

    public byte[] toBytes() {
        byte[] out = new byte[Long.BYTES + Integer.BYTES];
        writeLong(out, 0, correlationId);
        ORAMUtils.serializeInteger(pathId, out, Long.BYTES);
        return out;
    }

    public static ReadPathRequestMessage fromBytes(byte[] input) {
        long correlationId = readLong(input, 0);
        int pathId = ORAMUtils.deserializeInteger(input, Long.BYTES);
        return new ReadPathRequestMessage(correlationId, pathId);
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
