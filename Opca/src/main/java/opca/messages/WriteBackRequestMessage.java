package opca.messages;

import oram.common.ORAMUtils;
import oram.server.structure.EncryptedBucket;

import java.util.LinkedHashMap;
import java.util.Map;

public class WriteBackRequestMessage {
    private final int writeBackRound;
    private final Map<Integer, EncryptedBucket> buckets;

    public WriteBackRequestMessage(int writeBackRound, Map<Integer, EncryptedBucket> buckets) {
        this.writeBackRound = writeBackRound;
        this.buckets = buckets;
    }

    public int getWriteBackRound() {
        return writeBackRound;
    }

    public Map<Integer, EncryptedBucket> getBuckets() {
        return buckets;
    }

    public byte[] toBytes() {
        int total = Integer.BYTES + Integer.BYTES;
        for (Map.Entry<Integer, EncryptedBucket> entry : buckets.entrySet()) {
            EncryptedBucket bucket = entry.getValue();
            total += Integer.BYTES + Integer.BYTES + Integer.BYTES;
            for (byte[] block : bucket.getBlocks()) {
                total += Integer.BYTES + block.length;
            }
        }

        byte[] out = new byte[total];
        int offset = 0;

        ORAMUtils.serializeInteger(writeBackRound, out, offset);
        offset += Integer.BYTES;

        ORAMUtils.serializeInteger(buckets.size(), out, offset);
        offset += Integer.BYTES;

        for (Map.Entry<Integer, EncryptedBucket> entry : buckets.entrySet()) {
            int bucketId = entry.getKey();
            EncryptedBucket bucket = entry.getValue();

            ORAMUtils.serializeInteger(bucketId, out, offset);
            offset += Integer.BYTES;

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

    public static WriteBackRequestMessage fromBytes(byte[] input) {
        int offset = 0;

        int writeBackRound = ORAMUtils.deserializeInteger(input, offset);
        offset += Integer.BYTES;

        int numBuckets = ORAMUtils.deserializeInteger(input, offset);
        offset += Integer.BYTES;

        Map<Integer, EncryptedBucket> buckets = new LinkedHashMap<>(numBuckets);

        for (int i = 0; i < numBuckets; i++) {
            int bucketId = ORAMUtils.deserializeInteger(input, offset);
            offset += Integer.BYTES;

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

            buckets.put(bucketId, new EncryptedBucket(blocks, location));
        }

        return new WriteBackRequestMessage(writeBackRound, buckets);
    }
}
