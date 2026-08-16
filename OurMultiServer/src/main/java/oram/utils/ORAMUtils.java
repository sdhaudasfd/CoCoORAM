package oram.utils;

import oram.utils.ServerOperationType;

public class ORAMUtils {
    public static void serializeInteger(int value, byte[] output, int startOffset) {
        output[startOffset] = (byte) (value >>> 24);
        output[startOffset + 1] = (byte) (value >>> 16);
        output[startOffset + 2] = (byte) (value >>> 8);
        output[startOffset + 3] = (byte) value;
    }

    public static int deserializeInteger(byte[] input, int startOffset) {
        int value = Byte.toUnsignedInt(input[startOffset]);
        value = (value << 8) | Byte.toUnsignedInt(input[startOffset + 1]);
        value = (value << 8) | Byte.toUnsignedInt(input[startOffset + 2]);
        value = (value << 8) | Byte.toUnsignedInt(input[startOffset + 3]);
        return value;
    }

    public static byte[] serializeRequest(ServerOperationType operation, RawCustomExternalizable request) {
        int dataSize = 1 + request.getSerializedSize();
        byte[] serializedRequest = new byte[dataSize];
        serializedRequest[0] = (byte) operation.ordinal();

        int offset = request.writeExternal(serializedRequest, 1);
        if (offset != dataSize) {
            throw new IllegalStateException("Failed to serialize request " + operation);
        }

        return serializedRequest;
    }

    public static int computeHashCode(byte[] values) {
        int hash = values.length * 7;
        for (byte value : values) {
            hash = hash * 31 + value;
        }
        return hash;
    }
}
