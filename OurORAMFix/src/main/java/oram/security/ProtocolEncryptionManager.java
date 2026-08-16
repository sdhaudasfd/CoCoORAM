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
public class ProtocolEncryptionManager {
    private final EncryptionAbstraction encryptionAbstraction;

    public ProtocolEncryptionManager() {
        this.encryptionAbstraction = new EncryptionAbstraction("oram");
    }

    // Encryption form: (bucketID, Cipher[0], Cipher[1], ..., Cipher[Z-1])
    public EncryptedORAMBucket encryptBucket(ORAMBucket bucket) {
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
