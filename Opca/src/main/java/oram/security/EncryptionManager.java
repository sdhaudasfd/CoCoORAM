package oram.security;

import oram.common.Block;
import oram.common.Bucket;
import oram.common.ORAMContext;
import oram.common.ORAMUtils;
import oram.common.Stash;
import oram.server.structure.EncryptedBucket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

public class EncryptionManager {
    private final Logger logger = LoggerFactory.getLogger("oram");
    private final EncryptionAbstraction encryptionAbstraction;

    public EncryptionManager() {
        this.encryptionAbstraction = new EncryptionAbstraction("oram");
    }

    public EncryptedBucket encryptBucket(ORAMContext context, Bucket bucket) {
        prepareBucket(context, bucket);
        Block[] bucketContents = bucket.getBlocks();
        byte[][] encryptedBlocks = new byte[bucketContents.length][];
        for (int i = 0; i < bucketContents.length; i++) {
            Block block = bucketContents[i];
            byte[] serializedBlock = new byte[block.getSerializedSize()];
            block.writeExternal(serializedBlock, 0);
            encryptedBlocks[i] = encryptionAbstraction.encrypt(serializedBlock);
        }
        return new EncryptedBucket(encryptedBlocks, bucket.getLocation());
    }

    public Bucket decryptBucket(ORAMContext context, EncryptedBucket encryptedBucket) {
        if (encryptedBucket == null) {
            return null;
        }
        byte[][] blocks = encryptedBucket.getBlocks();
        Bucket newBucket = new Bucket(context.getBucketSize(), context.getBlockSize(), encryptedBucket.getLocation());
        for (int i = 0; i < blocks.length; i++) {
            byte[] block = blocks[i];
            byte[] serializedBlock = encryptionAbstraction.decrypt(block);
            if (serializedBlock == null) {
                logger.warn("Failed to decrypt block in bucket {}", encryptedBucket.getLocation());
                continue;
            }
            Block deserializedBlock = new Block(context.getBlockSize());
            try {
                deserializedBlock.readExternal(serializedBlock, 0);
            } catch (IllegalArgumentException e) {
                logger.warn("Corrupted block payload in bucket {}", encryptedBucket.getLocation(), e);
                continue;
            }
            if (deserializedBlock.getAddress() != ORAMUtils.DUMMY_ADDRESS
                    && !Arrays.equals(deserializedBlock.getContent(), ORAMUtils.DUMMY_BLOCK)) {
                newBucket.putBlock(i, deserializedBlock);
            }
        }
        return newBucket;
    }

    public byte[] encryptBlock(Block block) {
        byte[] serializedBlock = new byte[block.getSerializedSize()];
        block.writeExternal(serializedBlock, 0);
        return encryptionAbstraction.encrypt(serializedBlock);
    }

    public Block decryptBlock(byte[] encryptedBlock, int blockSize) {
        byte[] serializedBlock = encryptionAbstraction.decrypt(encryptedBlock);
        if (serializedBlock == null) {
            return null;
        }
        Block block = new Block(blockSize);
        block.readExternal(serializedBlock, 0);
        return block;
    }

    public byte[] encryptStash(Stash stash) {
        int dataSize = stash.getSerializedSize();
        byte[] serializedStash = new byte[dataSize];
        stash.writeExternal(serializedStash, 0);
        return encryptionAbstraction.encrypt(serializedStash);
    }

    public Stash decryptStash(byte[] encryptedStash, int blockSize) {
        byte[] serializedStash = encryptionAbstraction.decrypt(encryptedStash);
        if (serializedStash == null) {
            return null;
        }
        Stash stash = new Stash(blockSize);
        stash.readExternal(serializedStash, 0);
        return stash;
    }

    private void prepareBucket(ORAMContext context, Bucket bucket) {
        Block[] blocks = bucket.getBlocks();
        for (int i = 0; i < blocks.length; i++) {
            if (blocks[i] == null) {
                blocks[i] = new Block(
                        context.getBlockSize(),
                        ORAMUtils.DUMMY_ADDRESS,
                        ORAMUtils.DUMMY_VERSION,
                        ORAMUtils.DUMMY_BLOCK
                );
            }
        }
    }
}
