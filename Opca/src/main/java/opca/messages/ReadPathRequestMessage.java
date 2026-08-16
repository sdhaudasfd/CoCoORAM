package opca.messages;

import oram.common.ORAMUtils;

public class ReadPathRequestMessage {
    private final long correlationId;
    private final int pathId;
    private final boolean fakeRead;

    public ReadPathRequestMessage(long correlationId, int pathId, boolean fakeRead) {
        this.correlationId = correlationId;
        this.pathId = pathId;
        this.fakeRead = fakeRead;
    }

    public long getCorrelationId() {
        return correlationId;
    }

    public int getPathId() {
        return pathId;
    }

    public boolean isFakeRead() {
        return fakeRead;
    }

    public byte[] toBytes() {
        byte[] out = new byte[Long.BYTES + Integer.BYTES + 1];
        writeLong(out, 0, correlationId);
        ORAMUtils.serializeInteger(pathId, out, Long.BYTES);
        out[Long.BYTES + Integer.BYTES] = fakeRead ? (byte) 1 : (byte) 0;
        return out;
    }

    public static ReadPathRequestMessage fromBytes(byte[] input) {
        long correlationId = readLong(input, 0);
        int pathId = ORAMUtils.deserializeInteger(input, Long.BYTES);
        boolean fake = input[Long.BYTES + Integer.BYTES] != 0;
        return new ReadPathRequestMessage(correlationId, pathId, fake);
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
