package oram.server;

import oram.security.ProtocolEncryptionManager;
import oram.structure.EncryptedGlobalMapEntry;
import oram.structure.EncryptedORAMBucket;
import oram.structure.EncryptedORAMPath;
import oram.structure.GlobalMapEntry;
import oram.structure.ORAMBlock;
import oram.structure.ORAMBucket;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

public class StrongMVPState {
    private final int treeHeight;
    private final int rootBucketSize;
    private final int bucketSize;
    private final int blockSize;
    private final int bidSpace;
    private final int leafCount;
    private final ProtocolEncryptionManager encryptionManager;
    private final EncryptedORAMBucket[] encryptedTree;
    private final EncryptedGlobalMapEntry[] initialPositionMap;
    private final SecureRandom random;
    private final AtomicInteger nextSeq;

    public StrongMVPState(int treeHeight,
                          int rootBucketSize,
                          int bucketSize,
                          int blockSize,
                          int bidSpace) {
        if (treeHeight <= 0 || treeHeight >= 31) {
            throw new IllegalArgumentException("treeHeight must be in [1, 30]");
        }
        if (rootBucketSize <= 0 || bucketSize <= 0 || blockSize <= 0 || bidSpace <= 0) {
            throw new IllegalArgumentException("sizes must be positive");
        }

        this.treeHeight = treeHeight;
        this.rootBucketSize = rootBucketSize;
        this.bucketSize = bucketSize;
        this.blockSize = blockSize;
        this.bidSpace = bidSpace;
        this.leafCount = 1 << (treeHeight - 1);
        this.encryptionManager = new ProtocolEncryptionManager();
        this.encryptedTree = new EncryptedORAMBucket[(1 << treeHeight) - 1];
        this.initialPositionMap = new EncryptedGlobalMapEntry[bidSpace];
        this.random = new SecureRandom();
        this.nextSeq = new AtomicInteger(0);

        setup();
    }

    private void setup() {
        ORAMBucket[] plainTree = new ORAMBucket[encryptedTree.length];
        for (int bucketId = 0; bucketId < plainTree.length; bucketId++) {
            int level = levelOfBucket(bucketId);
            int size = level == 0 ? rootBucketSize : bucketSize;
            plainTree[bucketId] = new ORAMBucket(bucketId, size, blockSize);
        }

        for (int bid = 0; bid < bidSpace; bid++) {
            int pid = random.nextInt(leafCount);
            ORAMBlock block = new ORAMBlock(bid, initialDataForBid(bid), pid, 0, false);
            placeBlockOnPathBottomUp(plainTree, pid, block);
            initialPositionMap[bid] = encryptionManager.encryptGlobalMapEntry(new GlobalMapEntry(pid, 0));
        }

        for (int i = 0; i < plainTree.length; i++) {
            encryptedTree[i] = encryptionManager.encryptBucket(plainTree[i]);
        }
    }

    public EncryptedGlobalMapEntry[] initializePositionMap() {
        return copyPositionMap(initialPositionMap);
    }

    public int registerAccess() {
        return nextSeq.getAndIncrement();
    }

    public EncryptedORAMPath readPath(int pid) {
        validatePid(pid);
        int[] bucketIds = computePathBucketIds(pid);
        EncryptedORAMBucket[] buckets = new EncryptedORAMBucket[bucketIds.length];
        for (int level = 0; level < bucketIds.length; level++) {
            buckets[level] = encryptedTree[bucketIds[level]];
        }
        return new EncryptedORAMPath(pid, buckets);
    }

    public void submitTurn(int seq, EncryptedORAMPath encryptedPath) {
        int pid = encryptedPath.getPid();
        validatePid(pid);
        int[] bucketIds = computePathBucketIds(pid);
        EncryptedORAMBucket[] buckets = encryptedPath.getBuckets();
        if (buckets.length != bucketIds.length) {
            throw new IllegalArgumentException("Path length mismatch for pid " + pid);
        }

        for (int level = 0; level < buckets.length; level++) {
            encryptedTree[bucketIds[level]] = buckets[level];
        }
    }

    public void submitDummyTurn(EncryptedORAMPath encryptedPath) {
        int pid = encryptedPath.getPid();
        validatePid(pid);
        int[] bucketIds = computePathBucketIds(pid);
        EncryptedORAMBucket[] buckets = encryptedPath.getBuckets();
        if (buckets.length != bucketIds.length) {
            throw new IllegalArgumentException("Path length mismatch for dummy pid " + pid);
        }

        for (int level = 0; level < buckets.length; level++) {
            int bucketId = bucketIds[level];
            if (sameBucket(encryptedTree[bucketId], buckets[level])) {
                encryptedTree[bucketId] = buckets[level];
            }
        }
    }

    private boolean sameBucket(EncryptedORAMBucket a, EncryptedORAMBucket b) {
        if (a == null || b == null || a.getBucketId() != b.getBucketId()) {
            return false;
        }

        byte[][] aBlocks = a.getEncryptedBlocks();
        byte[][] bBlocks = b.getEncryptedBlocks();
        if (aBlocks.length != bBlocks.length) {
            return false;
        }

        for (int i = 0; i < aBlocks.length; i++) {
            if (!Arrays.equals(aBlocks[i], bBlocks[i])) {
                return false;
            }
        }
        return true;
    }

    private byte[] initialDataForBid(int bid) {
        byte[] data = new byte[blockSize];
        byte[] source = ("block-" + bid).getBytes(StandardCharsets.UTF_8);
        System.arraycopy(source, 0, data, 0, Math.min(source.length, data.length));
        return data;
    }

    private void placeBlockOnPathBottomUp(ORAMBucket[] plainTree, int pid, ORAMBlock block) {
        int[] bucketIds = computePathBucketIds(pid);
        for (int i = bucketIds.length - 1; i >= 0; i--) {
            ORAMBucket bucket = plainTree[bucketIds[i]];
            for (int slot = 0; slot < bucket.size(); slot++) {
                if (bucket.getBlock(slot).isDummy()) {
                    bucket.setBlock(slot, block);
                    return;
                }
            }
        }
        throw new IllegalStateException("No free slot on path for bid " + block.getBid());
    }

    private int[] computePathBucketIds(int pid) {
        int[] bucketIds = new int[treeHeight];
        int current = ((1 << (treeHeight - 1)) - 1) + pid;
        for (int level = treeHeight - 1; level >= 0; level--) {
            bucketIds[level] = current;
            current = (current - 1) >> 1;
        }
        return bucketIds;
    }

    private int levelOfBucket(int bucketId) {
        int level = 0;
        int remaining = bucketId + 1;
        while (remaining > 1) {
            remaining >>= 1;
            level++;
        }
        return level;
    }

    private void validatePid(int pid) {
        if (pid < 0 || pid >= leafCount) {
            throw new IllegalArgumentException("Invalid pid " + pid + ", leafCount=" + leafCount);
        }
    }

    private EncryptedGlobalMapEntry[] copyPositionMap(EncryptedGlobalMapEntry[] source) {
        EncryptedGlobalMapEntry[] copy = new EncryptedGlobalMapEntry[source.length];
        for (int i = 0; i < source.length; i++) {
            byte[] ciphertext = source[i].getCiphertext();
            byte[] ciphertextCopy = new byte[ciphertext.length];
            System.arraycopy(ciphertext, 0, ciphertextCopy, 0, ciphertext.length);
            copy[i] = new EncryptedGlobalMapEntry(ciphertextCopy);
        }
        return copy;
    }

}
