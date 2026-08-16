package oram.server;

import oram.security.WeightedEncryptionManager;
import oram.sse.SSEIndex;
import oram.sse.SSEIndexBuilder;
import oram.structure.EncryptedORAMBucket;
import oram.structure.EncryptedORAMPath;
import oram.structure.ORAMBlock;
import oram.structure.ORAMBucket;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public final class BlockSSEState {
    private final int treeHeight;
    private final int rootBucketSize;
    private final int bucketSize;
    private final int blockSize;
    private final int leafCount;
    private final WeightedEncryptionManager encryptionManager;
    private final EncryptedORAMBucket[] encryptedTree;
    private final AtomicInteger nextSeq = new AtomicInteger();
    private final SSEIndex index;

    public BlockSSEState(int bidExponent,
                         int rootBucketSize,
                         int bucketSize,
                         int blockSize,
                         Path datasetPath) throws IOException {
        if (bidExponent <= 0 || bidExponent >= 30) {
            throw new IllegalArgumentException("bidExponent must be in [1, 29]");
        }
        if (rootBucketSize <= 0 || bucketSize <= 0 || blockSize <= 0) {
            throw new IllegalArgumentException("Bucket and block sizes must be positive");
        }
        this.treeHeight = bidExponent + 1;
        this.rootBucketSize = rootBucketSize;
        this.bucketSize = bucketSize;
        this.blockSize = blockSize;
        this.leafCount = 1 << bidExponent;
        this.encryptionManager = new WeightedEncryptionManager();
        this.encryptedTree = new EncryptedORAMBucket[(1 << treeHeight) - 1];
        this.index = SSEIndexBuilder.build(
                datasetPath,
                blockSize,
                leafCount,
                Long.getLong("oram.sse.seed", 0x5EEDC0DEL)
        );
        if (index.getChunkCount() > leafCount) {
            throw new IllegalArgumentException(
                    "Dataset requires " + index.getChunkCount() +
                            " chunks but bid space is only " + leafCount
            );
        }
        setup();
    }

    private void setup() {
        ORAMBucket[] plainTree = new ORAMBucket[encryptedTree.length];
        for (int bucketId = 0; bucketId < plainTree.length; bucketId++) {
            int slots = bucketId == 0 ? rootBucketSize : bucketSize;
            plainTree[bucketId] = ORAMBucket.weighted(
                    bucketId,
                    Math.multiplyExact(slots, blockSize),
                    new ORAMBlock[0]
            );
        }

        List<ORAMBlock> blocks = new ArrayList<>();
        for (Map.Entry<Integer, byte[]> entry : index.getEncodedChunks().entrySet()) {
            int bid = entry.getKey();
            int pid = SSEIndexBuilder.deterministicPid(
                    bid,
                    leafCount,
                    Long.getLong("oram.sse.seed", 0x5EEDC0DEL)
            );
            blocks.add(new ORAMBlock(bid, entry.getValue(), pid, 0, false));
        }
        blocks.sort(Comparator.comparingInt((ORAMBlock b) -> b.getData().length).reversed());
        for (ORAMBlock block : blocks) {
            placeBlockOnPathBottomUp(plainTree, block);
        }

        for (int i = 0; i < plainTree.length; i++) {
            encryptedTree[i] = encryptionManager.encryptBucket(plainTree[i]);
        }
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

    public void submitTurn(EncryptedORAMPath encryptedPath) {
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

    public SSEIndex getIndex() {
        return index;
    }

    public int getLeafCount() {
        return leafCount;
    }

    private void placeBlockOnPathBottomUp(ORAMBucket[] tree, ORAMBlock block) {
        int[] bucketIds = computePathBucketIds(block.getPid());
        for (int level = bucketIds.length - 1; level >= 0; level--) {
            ORAMBucket bucket = tree[bucketIds[level]];
            if (bucket.getUsedBytes() + block.getData().length <= bucket.getCapacityBytes()) {
                ORAMBlock[] current = bucket.getBlocks();
                ORAMBlock[] updated = new ORAMBlock[current.length + 1];
                System.arraycopy(current, 0, updated, 0, current.length);
                updated[current.length] = block;
                bucket.setBlocks(updated);
                return;
            }
        }
        throw new IllegalStateException(
                "Initial weighted tree overflow for chunk bid=" + block.getBid() +
                        ", bytes=" + block.getData().length
        );
    }

    private int[] computePathBucketIds(int pid) {
        int[] bucketIds = new int[treeHeight];
        int current = (leafCount - 1) + pid;
        for (int level = treeHeight - 1; level >= 0; level--) {
            bucketIds[level] = current;
            current = (current - 1) >> 1;
        }
        return bucketIds;
    }

    private void validatePid(int pid) {
        if (pid < 0 || pid >= leafCount) {
            throw new IllegalArgumentException("Invalid pid " + pid);
        }
    }
}
