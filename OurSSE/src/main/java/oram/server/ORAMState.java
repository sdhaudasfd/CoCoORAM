package oram.server;

import oram.security.ProtocolEncryptionManager;
import oram.structure.EncryptedGlobalMapEntry;
import oram.structure.EncryptedBucketSegment;
import oram.structure.EncryptedORAMBucket;
import oram.structure.GlobalMapEntry;
import oram.structure.ORAMBlock;
import oram.structure.ORAMBucket;
import oram.structure.ORAMPath;
import oram.structure.RLSlot;
import oram.structure.UpdateMapEntry;
import oram.structure.EncryptedORAMPath;
import oram.sse.SSEChunkCodec;
import oram.sse.SSEIndex;
import oram.sse.SSEIndexBuilder;

import java.util.ArrayList;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.io.IOException;

public class ORAMState {
    private final int c;
    private final int treeHeight;
    private final int rootBucketSize;
    private final int competitionBucketSize;
    private final int bucketSize;
    private final int blockSize;
    private final int bidSpace;
    private final int leafCount;
    private final int[] levelBucketSizes;
    private final boolean weightedMode;

    private final ProtocolEncryptionManager encryptionManager;
    private final EncryptedORAMBucket[] encryptedTree;
    private final EncryptedGlobalMapEntry[] encryptedGbMp;
    private final List<byte[]> encryptedQL;
    private byte[][] encryptedLastRL;
    private byte[][] encryptedCurRL;
    private byte[][] encryptedLastUdMp;
    private byte[][] encryptedCurUdMp;

    private int curWrittenCount;
    private final Object curLock = new Object();

    private final java.security.SecureRandom random;

    public ORAMState(int c, int treeHeight, int rootBucketSize, int competitionBucketSize, int bucketSize, int blockSize, int bidSpace) {
        if (c <= 0) {
            throw new IllegalArgumentException("c must be positive");
        }
        if (treeHeight <= 0 || treeHeight >= 31) {
            throw new IllegalArgumentException("treeHeight must be in [1, 30]");
        }
        if (rootBucketSize <= 0) {
            throw new IllegalArgumentException("rootBucketSize must be positive");
        }
        if (bucketSize <= 0) {
            throw new IllegalArgumentException("bucketSize must be positive");
        }
        if (competitionBucketSize <= 0) {
            throw new IllegalArgumentException("competitionBucketSize must be positive");
        }
        if (blockSize <= 0) {
            throw new IllegalArgumentException("blockSize must be positive");
        }
        if (bidSpace <= 0) {
            throw new IllegalArgumentException("bidSpace must be positive");
        }

        this.c = c;
        this.treeHeight = treeHeight;
        this.competitionBucketSize = competitionBucketSize;
        this.bucketSize = bucketSize;
        this.rootBucketSize = rootBucketSize;
        this.blockSize = blockSize;
        this.bidSpace = bidSpace;
        this.leafCount = 1 << (treeHeight - 1);
        String configuredDataset = System.getProperty("oram.sse.dataset");
        this.weightedMode = configuredDataset != null && !configuredDataset.trim().isEmpty();
        this.levelBucketSizes = new int[treeHeight];

        for (int level = 0; level < treeHeight; level++) {
            levelBucketSizes[level] = weightedMode
                    ? weightedRegionCount(level)
                    : bucketCapacity(level);
        }

        this.encryptionManager = new ProtocolEncryptionManager();
        this.encryptedTree = new EncryptedORAMBucket[computeBucketCount(treeHeight)];
        this.encryptedGbMp = new EncryptedGlobalMapEntry[bidSpace];
        this.encryptedQL = new ArrayList<>();


        this.random = new java.security.SecureRandom();

        setup();
    }

    private void setup() {
        ORAMBucket[] plainTree = new ORAMBucket[encryptedTree.length];

        for (int bucketId = 0; bucketId < plainTree.length; bucketId++) {
            int level = levelOfBucket(bucketId);
            if (weightedMode) {
                plainTree[bucketId] = ORAMBucket.weighted(
                        bucketId,
                        weightedRegionCount(level) * weightedRegionCapacity(level),
                        new ORAMBlock[0]
                );
            } else {
                plainTree[bucketId] = new ORAMBucket(bucketId, levelBucketSizes[level], blockSize);
            }
        }

        String datasetPath = System.getProperty("oram.sse.dataset");
        if (datasetPath == null || datasetPath.trim().isEmpty()) {
            initializeSyntheticBlocks(plainTree);
        } else {
            initializeSseBlocks(plainTree, datasetPath);
        }

        for (int i = 0; i < plainTree.length; i++) {
            if (weightedMode) {
                int level = levelOfBucket(i);
                encryptedTree[i] = encryptionManager.encryptWeightedBucket(
                        plainTree[i],
                        weightedRegionCapacity(level),
                        weightedRegionCount(level)
                );
            } else {
                encryptedTree[i] = encryptionManager.encryptBucket(plainTree[i]);
            }
        }

        initializeEncryptedRoundState();

    }

    private void initializeSyntheticBlocks(ORAMBucket[] plainTree) {
        for (int bid = 0; bid < bidSpace; bid++) {
            int pid = random.nextInt(leafCount);
            ORAMBlock block = new ORAMBlock(
                    bid,
                    initialDataForBid(bid),
                    pid,
                    0,
                    false
            );

            placeBlockOnPathBottomUp(plainTree, pid, block);
            encryptedGbMp[bid] = encryptionManager.encryptGlobalMapEntry(new GlobalMapEntry(pid, 0));
        }
    }

    private void initializeSseBlocks(ORAMBucket[] plainTree, String datasetPath) {
        long seed = Long.getLong("oram.sse.seed", 0x5EEDC0DEL);
        SSEIndex index;
        try {
            index = SSEIndexBuilder.build(Paths.get(datasetPath), blockSize, leafCount, seed);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to build SSE index from " + datasetPath, e);
        }

        int requiredBids = index.getChunkCount() + c;
        if (requiredBids > bidSpace) {
            throw new IllegalStateException(
                    "SSE index requires " + requiredBids + " bids, but bidSpace is " + bidSpace
            );
        }

        for (int bid = 0; bid < bidSpace; bid++) {
            int pid = SSEIndexBuilder.deterministicPid(bid, leafCount, seed);
            encryptedGbMp[bid] = encryptionManager.encryptGlobalMapEntry(new GlobalMapEntry(pid, 0));
        }

        for (java.util.Map.Entry<Integer, byte[]> entry : index.getEncodedChunks().entrySet()) {
            int bid = entry.getKey();
            int pid = SSEIndexBuilder.deterministicPid(bid, leafCount, seed);
            placeBlockOnPathBottomUp(
                    plainTree,
                    pid,
                    new ORAMBlock(bid, entry.getValue(), pid, 0, false)
            );
        }

        for (int clientSlot = 0; clientSlot < c; clientSlot++) {
            int bid = index.getChunkCount() + clientSlot;
            int pid = SSEIndexBuilder.deterministicPid(bid, leafCount, seed);
            placeBlockOnPathBottomUp(
                    plainTree,
                    pid,
                    new ORAMBlock(
                            bid,
                            SSEChunkCodec.scratchBlock(blockSize, clientSlot),
                            pid,
                            0,
                            false
                    )
            );
        }

        System.out.println(
                "-- SSE index initialized: dataset=" + datasetPath +
                        ", keywords=" + index.getKeywordIds().size() +
                        ", chunks=" + index.getChunkCount() +
                        ", scratchBlocks=" + c +
                        ", blockSize=" + blockSize
        );
    }

    private void initializeEncryptedRoundState() {
        encryptedLastRL = new byte[c][];
        encryptedCurRL = new byte[c][];
        encryptedLastUdMp = new byte[c][];
        encryptedCurUdMp = new byte[c][];
        curWrittenCount = 0;

        for (int i = 0; i < c; i++) {
            encryptedLastRL[i] = encryptionManager.encryptRLSlot(RLSlot.dummy(blockSize));
            encryptedCurRL[i] = encryptionManager.encryptRLSlot(RLSlot.dummy(blockSize));
            encryptedLastUdMp[i] = encryptionManager.encryptUpdateMapEntry(UpdateMapEntry.dummy());
            encryptedCurUdMp[i] = encryptionManager.encryptUpdateMapEntry(UpdateMapEntry.dummy());
        }
    }

    public EncryptedGlobalMapEntry[] getEncryptedGbMp() {
        EncryptedGlobalMapEntry[] copy = new EncryptedGlobalMapEntry[encryptedGbMp.length];

        for (int bid = 0; bid < encryptedGbMp.length; bid++) {
            EncryptedGlobalMapEntry entry = encryptedGbMp[bid];
            if (entry == null) {
                continue;
            }
            byte[] ciphertext = entry.getCiphertext();
            byte[] ciphertextCopy = new byte[ciphertext.length];
            System.arraycopy(ciphertext, 0, ciphertextCopy, 0, ciphertext.length);
            copy[bid] = new EncryptedGlobalMapEntry(ciphertextCopy);
        }

        return copy;
    }

    public byte[][] getEncryptedLastUdMp() {
        return copyCipherArray(encryptedLastUdMp);
    }

    public byte[][] getEncryptedLastRL() {
        return copyCipherArray(encryptedLastRL);
    }

    public List<byte[]> getEncryptedQL() {
        List<byte[]> copy = new ArrayList<>(encryptedQL.size());

        for (byte[] item : encryptedQL) {
            byte[] itemCopy = new byte[item.length];
            System.arraycopy(item, 0, itemCopy, 0, item.length);
            copy.add(itemCopy);
        }

        return copy;
    }

    private byte[][] copyCipherArray(byte[][] source) {
        byte[][] copy = new byte[source.length][];

        for (int i = 0; i < source.length; i++) {
            byte[] ciphertext = source[i];
            byte[] ciphertextCopy = new byte[ciphertext.length];
            System.arraycopy(ciphertext, 0, ciphertextCopy, 0, ciphertext.length);
            copy[i] = ciphertextCopy;
        }

        return copy;
    }

    private byte[] initialDataForBid(int bid) {
        byte[] data = new byte[blockSize];
        byte[] source = ("block-" + bid).getBytes(StandardCharsets.UTF_8);
        int copyLength = Math.min(source.length, data.length);
        System.arraycopy(source, 0, data, 0, copyLength);
        return data;
    }

    private void placeBlockOnPathBottomUp(ORAMBucket[] plainTree, int pid, ORAMBlock block) {
        int[] bucketIds = computePathBucketIds(pid);

        for (int i = bucketIds.length - 1; i >= 0; i--) {
            ORAMBucket bucket = plainTree[bucketIds[i]];
            if (tryPutBlockInFirstDummySlot(bucket, block)) {
                return;
            }
        }

        throw new IllegalStateException("No free slot on path for bid " + block.getBid() + ", pid=" + pid);
    }

    private int[] computePathBucketIds(int pid) {
        int[] bucketIds = new int[treeHeight];

        int current = leafStartBucketId() + pid;
        for (int level = treeHeight - 1; level >= 0; level--) {
            bucketIds[level] = current;
            current = (current - 1) >> 1;
        }

        return bucketIds;
    }

    private boolean tryPutBlockInFirstDummySlot(ORAMBucket bucket, ORAMBlock block) {
        if (bucket.isWeighted()) {
            if (bucket.getUsedBytes() + block.getData().length > bucket.getCapacityBytes()) {
                return false;
            }
            ORAMBlock[] current = bucket.getBlocks();
            ORAMBlock[] expanded = java.util.Arrays.copyOf(current, current.length + 1);
            expanded[current.length] = block;
            bucket.setBlocks(expanded);
            return true;
        }
        ORAMBlock[] blocks = bucket.getBlocks();
        for (int i = 0; i < blocks.length; i++) {
            if (blocks[i].isDummy()) {
                blocks[i] = block;
                return true;
            }
        }

        return false;
    }

    private int computeBucketCount(int height) {
        return (1 << height) - 1;
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

    private int digitReverse(int value, int bits) {
        return Integer.reverse(value) >>> (Integer.SIZE - bits);
    }

    private int contendersAtLevel(int level) {
        int groups = 1 << level;
        return groups >= c ? 1 : (c + groups - 1) / groups;
    }

    private boolean isCompetitionLevel(int level) {
        return contendersAtLevel(level) > 1;
    }

    private int segmentBaseSize(int level) {
        return level == 0 ? rootBucketSize : competitionBucketSize;
    }

    private int bucketCapacity(int level) {
        if (!isCompetitionLevel(level)) {
            return bucketSize;
        }
        return segmentBaseSize(level) * contendersAtLevel(level);
    }

    private int weightedRegionCount(int level) {
        return isCompetitionLevel(level) ? contendersAtLevel(level) : 1;
    }

    private int weightedRegionCapacity(int level) {
        int multiplier = isCompetitionLevel(level) ? segmentBaseSize(level) : bucketSize;
        return multiplier * blockSize;
    }

    private int contenderRankInBucket(int seq, int slot, int pid, int level) {
        int timestepBaseSeq = seq - slot;
        int shift = (treeHeight - 1) - level;
        int myPrefix = pid >> shift;
        int rank = 0;

        for (int otherSlot = 0; otherSlot < slot; otherSlot++) {
            int otherSeq = timestepBaseSeq + otherSlot;
            int otherPid = digitReverse(otherSeq % leafCount, treeHeight - 1);
            if ((otherPid >> shift) == myPrefix) {
                rank++;
            }
        }

        return rank;
    }

    private int leafStartBucketId() {
        return (1 << (treeHeight - 1)) - 1;
    }

    private void validatePid(int pid) {
        if (pid < 0 || pid >= leafCount) {
            throw new IllegalArgumentException("Invalid pid " + pid + ", leafCount=" + leafCount);
        }
    }

    public boolean updateCur(int seq, byte[] encryptedSlotCur, byte[] encryptedMpCur) {
        int slot = seq % c;

        encryptedCurRL[slot] = copyBytes(encryptedSlotCur);
        encryptedCurUdMp[slot] = copyBytes(encryptedMpCur);

        boolean complete;

        synchronized (curLock) {
            curWrittenCount++;
            complete = curWrittenCount == c;
        }

        if (!complete) {
            return false;
        }

        encryptedLastRL = copyCipherArray(encryptedCurRL);
        encryptedLastUdMp = copyCipherArray(encryptedCurUdMp);

        encryptedQL.clear();
        resetEncryptedCurState();
        return true;
    }

    private void resetEncryptedCurState() {
        encryptedCurRL = new byte[c][];
        encryptedCurUdMp = new byte[c][];
        curWrittenCount = 0;

        for (int i = 0; i < c; i++) {
            encryptedCurRL[i] = encryptionManager.encryptRLSlot(RLSlot.dummy(blockSize));
            encryptedCurUdMp[i] = encryptionManager.encryptUpdateMapEntry(UpdateMapEntry.dummy());
        }
    }

    private byte[] copyBytes(byte[] source) {
        byte[] copy = new byte[source.length];
        System.arraycopy(source, 0, copy, 0, source.length);
        return copy;
    }

    public long getServerTotalStorageBytes() {
        long total = 0L;
        total += sumEncryptedTreeBytes();
        total += sumEncryptedGbMpBytes();
        total += sumByteListBytes(encryptedQL);
        total += sumByteMatrixBytes(encryptedLastRL);
        total += sumByteMatrixBytes(encryptedCurRL);
        total += sumByteMatrixBytes(encryptedLastUdMp);
        total += sumByteMatrixBytes(encryptedCurUdMp);
        return total;
    }

    private long sumEncryptedTreeBytes() {
        long total = 0L;
        for (EncryptedORAMBucket bucket : encryptedTree) {
            if (bucket == null) {
                continue;
            }
            for (byte[] block : bucket.getEncryptedBlocks()) {
                if (block != null) {
                    total += block.length;
                }
            }
        }
        return total;
    }

    private long sumEncryptedGbMpBytes() {
        long total = 0L;
        for (EncryptedGlobalMapEntry entry : encryptedGbMp) {
            if (entry != null && entry.getCiphertext() != null) {
                total += entry.getCiphertext().length;
            }
        }
        return total;
    }

    private long sumByteListBytes(List<byte[]> source) {
        long total = 0L;
        for (byte[] item : source) {
            if (item != null) {
                total += item.length;
            }
        }
        return total;
    }

    private long sumByteMatrixBytes(byte[][] source) {
        long total = 0L;
        for (byte[] item : source) {
            if (item != null) {
                total += item.length;
            }
        }
        return total;
    }

    public EncryptedORAMPath readEncryptedPath(int pid) {
        validatePid(pid);

        int[] bucketIds = computePathBucketIds(pid);
        EncryptedORAMBucket[] buckets = new EncryptedORAMBucket[bucketIds.length];

        for (int i = 0; i < bucketIds.length; i++) {
            buckets[i] = encryptedTree[bucketIds[i]];
        }

        return new EncryptedORAMPath(pid, buckets);
    }

    public void submitEviction(int seq, EncryptedORAMPath encryptedPathPrime) {
        submitEviction(seq, encryptedPathPrime.getPid(), toSegments(encryptedPathPrime));
    }

    public void submitEviction(int seq, int pid, EncryptedBucketSegment[] encryptedSegments) {
        validatePid(pid);
        int slot = seq % c;
        int[] bucketIds = computePathBucketIds(pid);

        if (encryptedSegments.length != treeHeight) {
            throw new IllegalArgumentException("Path length mismatch for pid " + pid);
        }

        for (EncryptedBucketSegment segment : encryptedSegments) {
            int level = segment.getLevel();
            if (level < 0 || level >= treeHeight) {
                throw new IllegalStateException(
                        "Invalid segment level for pid=" + pid +
                                ", seq=" + seq +
                                ", level=" + level
                );
            }

            int[] range = submissionRange(seq, slot, pid, level);
            int start = range[0];
            int end = range[1];
            byte[][] existingEncryptedBlocks = encryptedTree[bucketIds[level]].getEncryptedBlocks();
            byte[][] submittedEncryptedBlocks = segment.getEncryptedBlocks();

            if (segment.getStart() != start || submittedEncryptedBlocks.length != end - start) {
                throw new IllegalStateException(
                        "Invalid submitted segment metadata for pid=" + pid +
                                ", seq=" + seq +
                                ", level=" + level +
                                ", expectedStart=" + start +
                                ", actualStart=" + segment.getStart() +
                                ", expectedLength=" + (end - start) +
                                ", actualLength=" + submittedEncryptedBlocks.length
                );
            }

            if (end > existingEncryptedBlocks.length) {
                throw new IllegalStateException(
                        "Invalid segment range for pid=" + pid +
                                ", seq=" + seq +
                                ", level=" + level +
                                ", segment=[" + start + "," + end + ")" +
                                ", existingBucketSize=" + existingEncryptedBlocks.length
                );
            }

            for (int i = start; i < end; i++) {
                existingEncryptedBlocks[i] = submittedEncryptedBlocks[i - start];
            }
        }
    }

    private int[] submissionRange(int seq, int slot, int pid, int level) {
        if (weightedMode) {
            if (!isCompetitionLevel(level)) {
                return new int[] { 0, 1 };
            }
            int rank = contenderRankInBucket(seq, slot, pid, level);
            return new int[] { rank, rank + 1 };
        }
        if (!isCompetitionLevel(level)) {
            return new int[] { 0, levelBucketSizes[level] };
        }

        int baseSize = segmentBaseSize(level);
        int rank = contenderRankInBucket(seq, slot, pid, level);
        int start = rank * baseSize;
        return new int[] { start, start + baseSize };
    }

    private EncryptedBucketSegment[] toSegments(EncryptedORAMPath encryptedPathPrime) {
        EncryptedORAMBucket[] buckets = encryptedPathPrime.getBuckets();
        EncryptedBucketSegment[] segments = new EncryptedBucketSegment[buckets.length];
        for (int level = 0; level < buckets.length; level++) {
            segments[level] = new EncryptedBucketSegment(level, 0, buckets[level].getEncryptedBlocks());
        }
        return segments;
    }

}
