package oram.security;

import oram.sse.SSEChunkCodec;
import oram.structure.EncryptedORAMBucket;
import oram.structure.EncryptedORAMPath;
import oram.structure.ORAMBlock;
import oram.structure.ORAMBucket;
import oram.structure.ORAMPath;
import oram.utils.ORAMUtils;

import java.util.ArrayList;
import java.util.List;

public final class WeightedEncryptionManager {
    private static final int REGION_MAGIC = 0x57524731;
    private static final int REGION_VERSION = 1;
    private static final int REGION_HEADER_BYTES = Integer.BYTES * 4;
    private static final int BLOCK_HEADER_BYTES = Integer.BYTES * 5;

    private final EncryptionAbstraction encryption = new EncryptionAbstraction("oram");

    public EncryptedORAMBucket encryptBucket(ORAMBucket bucket) {
        List<ORAMBlock> blocks = new ArrayList<>();
        for (ORAMBlock block : bucket.getBlocks()) {
            if (block != null && !block.isDummy()) {
                blocks.add(block);
            }
        }
        byte[] ciphertext = encryption.encrypt(
                serializeRegion(blocks, bucket.getCapacityBytes())
        );
        if (ciphertext == null) {
            throw new IllegalStateException("Failed to encrypt weighted bucket");
        }
        return new EncryptedORAMBucket(
                bucket.getBucketId(),
                bucket.getCapacityBytes(),
                new byte[][] { ciphertext }
        );
    }

    public ORAMBucket decryptBucket(EncryptedORAMBucket encryptedBucket) {
        List<ORAMBlock> blocks = new ArrayList<>();
        for (byte[] ciphertext : encryptedBucket.getEncryptedBlocks()) {
            byte[] plaintext = encryption.decrypt(ciphertext);
            if (plaintext == null) {
                throw new IllegalStateException("Failed to decrypt weighted bucket");
            }
            blocks.addAll(deserializeRegion(
                    plaintext,
                    encryptedBucket.getRegionCapacityBytes()
            ));
        }
        return ORAMBucket.weighted(
                encryptedBucket.getBucketId(),
                encryptedBucket.getRegionCapacityBytes(),
                blocks.toArray(new ORAMBlock[0])
        );
    }

    public ORAMPath decryptPath(EncryptedORAMPath encryptedPath) {
        EncryptedORAMBucket[] encryptedBuckets = encryptedPath.getBuckets();
        ORAMBucket[] buckets = new ORAMBucket[encryptedBuckets.length];
        for (int i = 0; i < encryptedBuckets.length; i++) {
            buckets[i] = decryptBucket(encryptedBuckets[i]);
        }
        return new ORAMPath(encryptedPath.getPid(), buckets);
    }

    public EncryptedORAMPath encryptPath(ORAMPath path) {
        ORAMBucket[] buckets = path.getBuckets();
        EncryptedORAMBucket[] encryptedBuckets = new EncryptedORAMBucket[buckets.length];
        for (int i = 0; i < buckets.length; i++) {
            encryptedBuckets[i] = encryptBucket(buckets[i]);
        }
        return new EncryptedORAMPath(path.getPid(), encryptedBuckets);
    }

    private byte[] serializeRegion(List<ORAMBlock> blocks, int capacity) {
        int used = 0;
        for (ORAMBlock block : blocks) {
            used += block.getData().length;
        }
        if (used > capacity) {
            throw new IllegalStateException(
                    "Weighted bucket overflow: used=" + used + ", capacity=" + capacity
            );
        }

        int maxBlocks = Math.max(1, capacity / SSEChunkCodec.HEADER_BYTES);
        byte[] plaintext = new byte[
                REGION_HEADER_BYTES + capacity + maxBlocks * BLOCK_HEADER_BYTES
        ];
        int offset = 0;
        ORAMUtils.serializeInteger(REGION_MAGIC, plaintext, offset);
        offset += Integer.BYTES;
        ORAMUtils.serializeInteger(REGION_VERSION, plaintext, offset);
        offset += Integer.BYTES;
        ORAMUtils.serializeInteger(capacity, plaintext, offset);
        offset += Integer.BYTES;
        ORAMUtils.serializeInteger(blocks.size(), plaintext, offset);
        offset += Integer.BYTES;
        for (ORAMBlock block : blocks) {
            offset = block.writeExternal(plaintext, offset);
        }
        if (offset > plaintext.length) {
            throw new IllegalStateException("Weighted region serialization overflow");
        }
        return plaintext;
    }

    private List<ORAMBlock> deserializeRegion(byte[] plaintext, int expectedCapacity) {
        int offset = 0;
        int magic = ORAMUtils.deserializeInteger(plaintext, offset);
        offset += Integer.BYTES;
        int version = ORAMUtils.deserializeInteger(plaintext, offset);
        offset += Integer.BYTES;
        int capacity = ORAMUtils.deserializeInteger(plaintext, offset);
        offset += Integer.BYTES;
        int count = ORAMUtils.deserializeInteger(plaintext, offset);
        offset += Integer.BYTES;
        if (magic != REGION_MAGIC || version != REGION_VERSION ||
                capacity != expectedCapacity || count < 0) {
            throw new IllegalStateException("Invalid weighted region");
        }

        List<ORAMBlock> blocks = new ArrayList<>();
        int used = 0;
        for (int i = 0; i < count; i++) {
            ORAMBlock block = new ORAMBlock();
            offset = block.readExternal(plaintext, offset);
            used += block.getData().length;
            blocks.add(block);
        }
        if (used > capacity) {
            throw new IllegalStateException("Decrypted weighted region exceeds capacity");
        }
        return blocks;
    }
}
