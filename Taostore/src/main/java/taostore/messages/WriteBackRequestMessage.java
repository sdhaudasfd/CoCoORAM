package taostore.messages;

import oram.common.ORAMUtils;
import oram.server.structure.EncryptedBucket;

import java.util.LinkedHashMap;
import java.util.Map;

public class WriteBackRequestMessage {
    private final int serverTimestamp;
    // pathId -> buckets(root->leaf)
    private final Map<Integer, EncryptedBucket[]> paths;

    public WriteBackRequestMessage(int serverTimestamp, Map<Integer, EncryptedBucket[]> paths) {
        this.serverTimestamp = serverTimestamp;
        this.paths = paths;
    }

    public int getServerTimestamp() {
        return serverTimestamp;
    }

    public Map<Integer, EncryptedBucket[]> getPaths() {
        return paths;
    }

    public byte[] toBytes() {
        int total = Integer.BYTES + Integer.BYTES;
        for (Map.Entry<Integer, EncryptedBucket[]> entry : paths.entrySet()) {
            total += Integer.BYTES + Integer.BYTES;
            for (EncryptedBucket bucket : entry.getValue()) {
                total += Integer.BYTES + Integer.BYTES;
                for (byte[] block : bucket.getBlocks()) {
                    total += Integer.BYTES + block.length;
                }
            }
        }

        byte[] out = new byte[total];
        int offset = 0;

        ORAMUtils.serializeInteger(serverTimestamp, out, offset);
        offset += Integer.BYTES;

        ORAMUtils.serializeInteger(paths.size(), out, offset);
        offset += Integer.BYTES;

        for (Map.Entry<Integer, EncryptedBucket[]> entry : paths.entrySet()) {
            ORAMUtils.serializeInteger(entry.getKey(), out, offset);
            offset += Integer.BYTES;

            EncryptedBucket[] buckets = entry.getValue();
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
        }

        return out;
    }

    public static WriteBackRequestMessage fromBytes(byte[] input) {
        int offset = 0;
        int serverTimestamp = ORAMUtils.deserializeInteger(input, offset);
        offset += Integer.BYTES;

        int numPaths = ORAMUtils.deserializeInteger(input, offset);
        offset += Integer.BYTES;

        Map<Integer, EncryptedBucket[]> paths = new LinkedHashMap<>(numPaths);
        for (int p = 0; p < numPaths; p++) {
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
            paths.put(pathId, buckets);
        }

        return new WriteBackRequestMessage(serverTimestamp, paths);
    }
}
