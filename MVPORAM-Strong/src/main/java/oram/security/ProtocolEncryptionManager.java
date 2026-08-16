package oram.security;

import oram.structure.EncryptedGlobalMapEntry;
import oram.structure.EncryptedMapUpdate;
import oram.structure.EncryptedORAMBucket;
import oram.structure.GlobalMapEntry;
import oram.structure.MapUpdate;
import oram.structure.ORAMBlock;
import oram.structure.ORAMBucket;
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

    public EncryptedMapUpdate encryptMapUpdate(MapUpdate update) {
        byte[] plaintext = new byte[update.getSerializedSize()];
        update.writeExternal(plaintext, 0);

        byte[] ciphertext = encryptionAbstraction.encrypt(plaintext);
        if (ciphertext == null) {
            throw new IllegalStateException("Failed to encrypt map update");
        }

        return new EncryptedMapUpdate(ciphertext);
    }

    public MapUpdate decryptMapUpdate(EncryptedMapUpdate encryptedUpdate) {
        byte[] plaintext = encryptionAbstraction.decrypt(encryptedUpdate.getCiphertext());
        if (plaintext == null) {
            throw new IllegalStateException("Failed to decrypt map update");
        }

        MapUpdate update = new MapUpdate();
        update.readExternal(plaintext, 0);
        return update;
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

}
