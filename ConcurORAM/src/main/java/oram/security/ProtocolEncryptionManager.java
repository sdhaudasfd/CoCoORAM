package oram.security;

import oram.structure.EncryptedGlobalMapEntry;
import oram.structure.EncryptedORAMBucket;
import oram.structure.GlobalMapEntry;
import oram.structure.ORAMBlock;
import oram.structure.ORAMBucket;
import oram.structure.UpdateMapEntry;
import oram.structure.EncryptedORAMPath;
import oram.structure.ORAMPath;
import oram.utils.ORAMUtils;

import java.security.SecureRandom;

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

    // Encryption form: Cipher(bid, pid, seq, dummy)
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

    public ORAMBlock[] decryptBlocks(byte[][] encryptedBlocks, int blockSize) {
        if (encryptedBlocks == null) {
            return new ORAMBlock[0];
        }
        ORAMBlock[] blocks = new ORAMBlock[encryptedBlocks.length];
        for (int i = 0; i < encryptedBlocks.length; i++) {
            blocks[i] = encryptedBlocks[i] == null
                    ? ORAMBlock.dummy(blockSize)
                    : decryptBlock(encryptedBlocks[i], blockSize);
        }
        return blocks;
    }

    public byte[][] encryptBlocks(ORAMBlock[] blocks) {
        byte[][] encrypted = new byte[blocks.length][];
        for (int i = 0; i < blocks.length; i++) {
            encrypted[i] = encryptBlock(blocks[i]);
        }
        return encrypted;
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

    public byte[] encryptBlock(ORAMBlock block) {
        byte[] plaintext = new byte[block.getSerializedSize()];
        block.writeExternal(plaintext, 0);
        byte[] ciphertext = encryptionAbstraction.encrypt(plaintext);
        if (ciphertext == null) {
            throw new IllegalStateException("Failed to encrypt ORAM block");
        }
        return ciphertext;
    }

    public ORAMBlock decryptBlock(byte[] ciphertext, int blockSize) {
        if (ciphertext == null || isAllZero(ciphertext)) {
            return ORAMBlock.dummy(blockSize);
        }
        byte[] plaintext = encryptionAbstraction.decrypt(ciphertext);
        if (plaintext == null) {
            throw new IllegalStateException("Failed to decrypt ORAM block");
        }
        ORAMBlock block = new ORAMBlock();
        block.readExternal(plaintext, 0);
        return block;
    }

    public byte[] encryptInt(int value, SecureRandom random) {
        byte[] plaintext = new byte[16];
        ORAMUtils.serializeInteger(value, plaintext, 0);
        byte[] nonce = new byte[plaintext.length - Integer.BYTES];
        random.nextBytes(nonce);
        System.arraycopy(nonce, 0, plaintext, Integer.BYTES, nonce.length);
        byte[] ciphertext = encryptionAbstraction.encrypt(plaintext);
        if (ciphertext == null) {
            throw new IllegalStateException("Failed to encrypt integer");
        }
        return ciphertext;
    }

    public int decryptInt(byte[] ciphertext) {
        byte[] plaintext = encryptionAbstraction.decrypt(ciphertext);
        if (plaintext == null) {
            throw new IllegalStateException("Failed to decrypt integer");
        }
        return ORAMUtils.deserializeInteger(plaintext, 0);
    }

    private boolean isAllZero(byte[] bytes) {
        for (byte value : bytes) {
            if (value != 0) {
                return false;
            }
        }
        return true;
    }
}
