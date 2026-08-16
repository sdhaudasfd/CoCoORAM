package taostore.messages;

import oram.common.ORAMUtils;
import oram.server.structure.EncryptedBucket;

public class ReadPathResponseMessage {
    private final long correlationId;
    private final int pathId;
    private final EncryptedBucket[] buckets;

    public ReadPathResponseMessage(long correlationId, int pathId, EncryptedBucket[] buckets) {
        this.correlationId = correlationId;
        this.pathId = pathId;
        this.buckets = buckets;
    }

    public long getCorrelationId() {
        return correlationId;
    }

    public int getPathId() {
        return pathId;
    }

    public EncryptedBucket[] getBuckets() {
        return buckets;
    }

    public byte[] toBytes() {
        int total = Long.BYTES + Integer.BYTES + Integer.BYTES;
        for (EncryptedBucket bucket : buckets) {
            int nBlocks = bucket.getBlocks().length;
            total += Integer.BYTES + Integer.BYTES; // location + numBlocks
            for (int i = 0; i < nBlocks; i++) {
                byte[] block = bucket.getBlocks()[i];
                total += Integer.BYTES + block.length;
            }
        }

        byte[] out = new byte[total];
        int offset = 0;

        writeLong(out, offset, correlationId);
        offset += Long.BYTES;

        ORAMUtils.serializeInteger(pathId, out, offset);
        offset += Integer.BYTES;

        ORAMUtils.serializeInteger(buckets.length, out, offset);
        offset += Integer.BYTES;

        for (EncryptedBucket bucket : buckets) {
            ORAMUtils.serializeInteger(bucket.getLocation(), out, offset);
            offset += Integer.BYTES;

            byte[][] blocks = bucket.getBlocks();
            ORAMUtils.serializeInteger(blocks.length, out, offset);
            offset += Integer.BYTES;

            for (byte[] block : blocks) {
                ORAMUtils.serializeInteger(block.length, out, offset);
                offset += Integer.BYTES;
                System.arraycopy(block, 0, out, offset, block.length);
                offset += block.length;
            }
        }

        return out;
    }

    public static ReadPathResponseMessage fromBytes(byte[] input) {
        int offset = 0;
        long correlationId = readLong(input, offset);
        offset += Long.BYTES;

        int pathId = ORAMUtils.deserializeInteger(input, offset);
        offset += Integer.BYTES;

        int numBuckets = ORAMUtils.deserializeInteger(input, offset);
        offset += Integer.BYTES;

        EncryptedBucket[] buckets = new EncryptedBucket[numBuckets];
        for (int i = 0; i < numBuckets; i++) {
            int location = ORAMUtils.deserializeInteger(input, offset);
            offset += Integer.BYTES;

            int numBlocks = ORAMUtils.deserializeInteger(input, offset);
            offset += Integer.BYTES;

            byte[][] blocks = new byte[numBlocks][];
            for (int j = 0; j < numBlocks; j++) {
                int len = ORAMUtils.deserializeInteger(input, offset);
                offset += Integer.BYTES;
                blocks[j] = new byte[len];
                System.arraycopy(input, offset, blocks[j], 0, len);
                offset += len;
            }
            buckets[i] = new EncryptedBucket(blocks, location);
        }

        return new ReadPathResponseMessage(correlationId, pathId, buckets);
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
