package taostore.messages;

public class DrainResponseMessage {
    private final long requestId;

    public DrainResponseMessage(long requestId) {
        this.requestId = requestId;
    }

    public long getRequestId() {
        return requestId;
    }

    public byte[] toBytes() {
        byte[] out = new byte[Long.BYTES];
        writeLong(out, 0, requestId);
        return out;
    }

    public static DrainResponseMessage fromBytes(byte[] input) {
        return new DrainResponseMessage(readLong(input, 0));
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
