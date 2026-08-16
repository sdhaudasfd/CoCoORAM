package oram.security;

import oram.structure.EncryptedGlobalMapEntry;
import oram.structure.EncryptedBucketSegment;
import oram.structure.EncryptedORAMBucket;
import oram.structure.GlobalMapEntry;
import oram.structure.ORAMBlock;
import oram.structure.ORAMBucket;
import oram.structure.RLSlot;
import oram.structure.UpdateMapEntry;
import oram.structure.EncryptedORAMPath;
import oram.structure.ORAMPath;
import oram.sse.SSEChunkCodec;

import java.util.ArrayList;
import java.util.List;

public class ProtocolEncryptionManager {
    private static final int WEIGHTED_REGION_MAGIC = 0x57524731;
    private static final int WEIGHTED_REGION_VERSION = 1;
    private static final int WEIGHTED_REGION_HEADER_BYTES = Integer.BYTES * 4;
    private static final int ORAM_BLOCK_HEADER_BYTES = Integer.BYTES * 5;
    private final EncryptionAbstraction encryptionAbstraction;

    public ProtocolEncryptionManager() {
        this.encryptionAbstraction = new EncryptionAbstraction("oram");
    }

    // Encryption form: (bucketID, Cipher[0], Cipher[1], ..., Cipher[Z-1])
    public EncryptedORAMBucket encryptBucket(ORAMBucket bucket) {
        if (bucket.isWeighted()) {
            return encryptWeightedBucket(bucket);
        }
        ORAMBlock[] blocks = bucket.getBlocks();
        byte[][] encryptedBlocks = new byte[blocks.length][];

        for (int i = 0; i < blocks.length; i++) {
            ORAMBlock block = blocks[i];
            byte[] serializedBlock = new byte[block.getSerializedSize()];
            block.writeExternal(serializedBlock, 0);

            byte[] ciphertext = encryptionAbstraction.encrypt(serializedBlock);
            if (ciphertext == null) {
                throw new IllegalStateException("Failed to encrypt block in bucket " + bucket.getBucketId());
            }

            encryptedBlocks[i] = ciphertext;
        }

        return new EncryptedORAMBucket(bucket.getBucketId(), encryptedBlocks);
    }

    public ORAMBucket decryptBucket(EncryptedORAMBucket encryptedBucket) {
        if (encryptedBucket.isWeighted()) {
            return decryptWeightedBucket(encryptedBucket);
        }
        byte[][] encryptedBlocks = encryptedBucket.getEncryptedBlocks();
        ORAMBlock[] blocks = new ORAMBlock[encryptedBlocks.length];

        for (int i = 0; i < encryptedBlocks.length; i++) {
            byte[] plaintext = encryptionAbstraction.decrypt(encryptedBlocks[i]);
            if (plaintext == null) {
                throw new IllegalStateException("Failed to decrypt block in bucket " + encryptedBucket.getBucketId());
            }

            ORAMBlock block = new ORAMBlock();
            block.readExternal(plaintext, 0);
            blocks[i] = block;
        }

        return new ORAMBucket(encryptedBucket.getBucketId(), blocks);
    }

    private EncryptedORAMBucket encryptWeightedBucket(ORAMBucket bucket) {
        int regionCapacity = bucket.getCapacityBytes();
        List<ORAMBlock> blocks = new ArrayList<>();
        for (ORAMBlock block : bucket.getBlocks()) {
            if (block != null && !block.isDummy()) {
                blocks.add(block);
            }
        }
        byte[] plaintext = serializeWeightedRegion(blocks, regionCapacity);
        byte[] ciphertext = encryptionAbstraction.encrypt(plaintext);
        if (ciphertext == null) {
            throw new IllegalStateException("Failed to encrypt weighted bucket " + bucket.getBucketId());
        }
        return new EncryptedORAMBucket(
                bucket.getBucketId(),
                regionCapacity,
                new byte[][] { ciphertext }
        );
    }

    public EncryptedORAMBucket encryptWeightedBucket(ORAMBucket bucket,
                                                      int regionCapacity,
                                                      int regionCount) {
        if (!bucket.isWeighted() || regionCapacity <= 0 || regionCount <= 0) {
            throw new IllegalArgumentException("Invalid weighted bucket layout");
        }

        List<List<ORAMBlock>> regions = new ArrayList<>();
        int[] used = new int[regionCount];
        for (int i = 0; i < regionCount; i++) {
            regions.add(new ArrayList<>());
        }

        for (ORAMBlock block : bucket.getBlocks()) {
            if (block == null || block.isDummy()) {
                continue;
            }
            int weight = block.getData().length;
            boolean placed = false;
            for (int region = 0; region < regionCount; region++) {
                if (used[region] + weight <= regionCapacity) {
                    regions.get(region).add(block);
                    used[region] += weight;
                    placed = true;
                    break;
                }
            }
            if (!placed) {
                throw new IllegalStateException(
                        "Cannot pack weighted block bid=" + block.getBid() +
                                ", weight=" + weight +
                                " into " + regionCount + " regions of " + regionCapacity + " bytes"
                );
            }
        }

        byte[][] encryptedRegions = new byte[regionCount][];
        for (int region = 0; region < regionCount; region++) {
            byte[] plaintext = serializeWeightedRegion(regions.get(region), regionCapacity);
            encryptedRegions[region] = encryptionAbstraction.encrypt(plaintext);
            if (encryptedRegions[region] == null) {
                throw new IllegalStateException("Failed to encrypt weighted region " + region);
            }
        }
        return new EncryptedORAMBucket(bucket.getBucketId(), regionCapacity, encryptedRegions);
    }

    private ORAMBucket decryptWeightedBucket(EncryptedORAMBucket encryptedBucket) {
        List<ORAMBlock> blocks = new ArrayList<>();
        for (byte[] encryptedRegion : encryptedBucket.getEncryptedBlocks()) {
            byte[] plaintext = encryptionAbstraction.decrypt(encryptedRegion);
            if (plaintext == null) {
                throw new IllegalStateException(
                        "Failed to decrypt weighted region in bucket " + encryptedBucket.getBucketId()
                );
            }
            blocks.addAll(deserializeWeightedRegion(
                    plaintext,
                    encryptedBucket.getRegionCapacityBytes()
            ));
        }
        int totalCapacity = encryptedBucket.getRegionCapacityBytes()
                * encryptedBucket.getEncryptedBlocks().length;
        return ORAMBucket.weighted(
                encryptedBucket.getBucketId(),
                totalCapacity,
                blocks.toArray(new ORAMBlock[0])
        );
    }

    private byte[] serializeWeightedRegion(List<ORAMBlock> blocks, int payloadCapacity) {
        int used = 0;
        for (ORAMBlock block : blocks) {
            used += block.getData().length;
        }
        if (used > payloadCapacity) {
            throw new IllegalStateException(
                    "Weighted region overflow: used=" + used + ", capacity=" + payloadCapacity
            );
        }

        int maxBlocks = Math.max(1, payloadCapacity / SSEChunkCodec.HEADER_BYTES);
        int plaintextSize = WEIGHTED_REGION_HEADER_BYTES
                + payloadCapacity
                + maxBlocks * ORAM_BLOCK_HEADER_BYTES;
        byte[] plaintext = new byte[plaintextSize];
        int offset = 0;
        offset = writeInt(WEIGHTED_REGION_MAGIC, plaintext, offset);
        offset = writeInt(WEIGHTED_REGION_VERSION, plaintext, offset);
        offset = writeInt(payloadCapacity, plaintext, offset);
        offset = writeInt(blocks.size(), plaintext, offset);
        for (ORAMBlock block : blocks) {
            offset = block.writeExternal(plaintext, offset);
        }
        if (offset > plaintext.length) {
            throw new IllegalStateException("Weighted region serialization exceeded fixed ciphertext size");
        }
        return plaintext;
    }

    private List<ORAMBlock> deserializeWeightedRegion(byte[] plaintext, int expectedCapacity) {
        int offset = 0;
        int magic = readInt(plaintext, offset);
        offset += Integer.BYTES;
        int version = readInt(plaintext, offset);
        offset += Integer.BYTES;
        int capacity = readInt(plaintext, offset);
        offset += Integer.BYTES;
        int count = readInt(plaintext, offset);
        offset += Integer.BYTES;
        if (magic != WEIGHTED_REGION_MAGIC || version != WEIGHTED_REGION_VERSION) {
            throw new IllegalStateException("Invalid weighted region encoding");
        }
        if (capacity != expectedCapacity || count < 0) {
            throw new IllegalStateException("Invalid weighted region metadata");
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

    private int writeInt(int value, byte[] output, int offset) {
        oram.utils.ORAMUtils.serializeInteger(value, output, offset);
        return offset + Integer.BYTES;
    }

    private int readInt(byte[] input, int offset) {
        return oram.utils.ORAMUtils.deserializeInteger(input, offset);
    }

    // Encryption form: Cipher(pid, seq)
    public EncryptedGlobalMapEntry encryptGlobalMapEntry(GlobalMapEntry entry) {
        byte[] plaintext = new byte[entry.getSerializedSize()];
        entry.writeExternal(plaintext, 0);

        byte[] ciphertext = encryptionAbstraction.encrypt(plaintext);
        if (ciphertext == null) {
            throw new IllegalStateException("Failed to encrypt global map entry");
        }

        return new EncryptedGlobalMapEntry(ciphertext);
    }

    public GlobalMapEntry decryptGlobalMapEntry(EncryptedGlobalMapEntry encryptedEntry) {
        byte[] plaintext = encryptionAbstraction.decrypt(encryptedEntry.getCiphertext());
        if (plaintext == null) {
            throw new IllegalStateException("Failed to decrypt global map entry");
        }

        GlobalMapEntry entry = new GlobalMapEntry();
        entry.readExternal(plaintext, 0);
        return entry;
    }

    // Encryption form: Cipher(bid, data, pid, seq, dummy)
    public byte[] encryptRLSlot(RLSlot slot) {
        byte[] plaintext = new byte[slot.getSerializedSize()];
        slot.writeExternal(plaintext, 0);

        byte[] ciphertext = encryptionAbstraction.encrypt(plaintext);
        if (ciphertext == null) {
            throw new IllegalStateException("Failed to encrypt RLSlot");
        }

        return ciphertext;
    }

    public RLSlot decryptRLSlot(byte[] ciphertext) {
        byte[] plaintext = encryptionAbstraction.decrypt(ciphertext);
        if (plaintext == null) {
            throw new IllegalStateException("Failed to decrypt RLSlot");
        }

        RLSlot slot = new RLSlot();
        slot.readExternal(plaintext, 0);
        return slot;
    }

    // Encryption form: Cipher(bid, rid, pid, seq, dummy)
    public byte[] encryptUpdateMapEntry(UpdateMapEntry entry) {
        byte[] plaintext = new byte[entry.getSerializedSize()];
        entry.writeExternal(plaintext, 0);

        byte[] ciphertext = encryptionAbstraction.encrypt(plaintext);
        if (ciphertext == null) {
            throw new IllegalStateException("Failed to encrypt UpdateMapEntry");
        }

        return ciphertext;
    }

    public UpdateMapEntry decryptUpdateMapEntry(byte[] ciphertext) {
        byte[] plaintext = encryptionAbstraction.decrypt(ciphertext);
        if (plaintext == null) {
            throw new IllegalStateException("Failed to decrypt UpdateMapEntry");
        }

        UpdateMapEntry entry = new UpdateMapEntry();
        entry.readExternal(plaintext, 0);
        return entry;
    }

    // Encryption form: Cipher(buckets)
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

    public EncryptedBucketSegment[] encryptPathSegments(ORAMPath path,
                                                        int seq,
                                                        int slot,
                                                        int c,
                                                        int treeHeight,
                                                        int rootBucketSize,
                                                        int competitionBucketSize,
                                                        int bucketSize,
                                                        int leafCount) {
        ORAMBucket[] buckets = path.getBuckets();
        EncryptedBucketSegment[] segments = new EncryptedBucketSegment[buckets.length];
        int timestepBaseSeq = seq - slot;
        int pid = path.getPid();

        for (int level = 0; level < buckets.length; level++) {
            if (buckets[level].isWeighted()) {
                int start = 0;
                if (isCompetitionLevel(c, level)) {
                    int shift = (treeHeight - 1) - level;
                    int myPrefix = pid >> shift;
                    int rank = 0;
                    for (int otherSlot = 0; otherSlot < slot; otherSlot++) {
                        int otherPid = Integer.reverse((timestepBaseSeq + otherSlot) & (leafCount - 1))
                                >>> (Integer.SIZE - (treeHeight - 1));
                        if ((otherPid >> shift) == myPrefix) {
                            rank++;
                        }
                    }
                    start = rank;
                }
                EncryptedORAMBucket encrypted = encryptWeightedBucket(buckets[level]);
                segments[level] = new EncryptedBucketSegment(
                        level,
                        start,
                        encrypted.getEncryptedBlocks()
                );
                continue;
            }
            ORAMBlock[] blocks = buckets[level].getBlocks();
            int start = 0;
            int end = blocks.length;

            if (isCompetitionLevel(c, level)) {
                int baseSize = level == 0 ? rootBucketSize : competitionBucketSize;
                int shift = (treeHeight - 1) - level;
                int myPrefix = pid >> shift;
                int rank = 0;

                for (int otherSlot = 0; otherSlot < slot; otherSlot++) {
                    int otherPid = Integer.reverse((timestepBaseSeq + otherSlot) & (leafCount - 1))
                            >>> (Integer.SIZE - (treeHeight - 1));
                    if ((otherPid >> shift) == myPrefix) {
                        rank++;
                    }
                }

                start = rank * baseSize;
                end = start + baseSize;
            }

            byte[][] encryptedBlocks = new byte[end - start][];
            for (int i = start; i < end; i++) {
                ORAMBlock block = blocks[i];
                byte[] serializedBlock = new byte[block.getSerializedSize()];
                block.writeExternal(serializedBlock, 0);
                byte[] ciphertext = encryptionAbstraction.encrypt(serializedBlock);
                if (ciphertext == null) {
                    throw new IllegalStateException("Failed to encrypt block in path segment, level=" + level);
                }
                encryptedBlocks[i - start] = ciphertext;
            }

            segments[level] = new EncryptedBucketSegment(level, start, encryptedBlocks);
        }

        return segments;
    }

    private boolean isCompetitionLevel(int c, int level) {
        int groups = 1 << level;
        int contenders = groups >= c ? 1 : (c + groups - 1) / groups;
        return contenders > 1;
    }

    public EncryptedGlobalMapEntry[] encryptGlobalMapEntries(GlobalMapEntry[] gbMp,
                                                            int bidStartInclusive,
                                                            int bidEndExclusive) {
        EncryptedGlobalMapEntry[] entries = new EncryptedGlobalMapEntry[bidEndExclusive - bidStartInclusive];

        for (int bid = bidStartInclusive; bid < bidEndExclusive; bid++) {
            if (bid < 0 || bid >= gbMp.length) {
                throw new IllegalStateException("Invalid bid " + bid + " for gbMp length " + gbMp.length);
            }
            GlobalMapEntry entry = gbMp[bid];
            if (entry == null) {
                throw new IllegalStateException("Missing gbMp entry for bid " + bid);
            }
            entries[bid - bidStartInclusive] = encryptGlobalMapEntry(entry);
        }

        return entries;
    }
}
