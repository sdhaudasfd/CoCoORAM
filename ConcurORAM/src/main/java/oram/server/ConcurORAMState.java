package oram.server;

import oram.security.ProtocolEncryptionManager;
import oram.structure.EncryptedGlobalMapEntry;
import oram.structure.EncryptedORAMBucket;
import oram.structure.EncryptedORAMPath;
import oram.structure.GlobalMapEntry;
import oram.structure.ORAMBlock;
import oram.structure.ORAMBucket;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

class ConcurORAMState {
    private final Object roundLock = new Object();
    private final Object evictionSessionLock = new Object();

    private final int c;
    private final int treeHeight;
    private final int leafCount;
    private final int bidSpace;
    private final int stashSize;
    private final int z;
    private final int s;
    private final int evictInterval;
    private final int bucketSize;
    private final int blockSize;
    private final int maxDrlLogs;
    private final int criticalLevels;
    private int evictionCounter;

    private final ProtocolEncryptionManager encryptionManager;
    private final EncryptedORAMBucket[] dataTree;
    private final EncryptedORAMBucket[] wotTree;
    final EncryptedGlobalMapEntry[] encryptedPositionMap;

    private final List<byte[]> queryLog;
    private final byte[][] currentDrl;
    private final byte[][] currentMapL;
    private volatile PublishedMap committedMap;
    private final CopyOnWriteArrayList<DrlLog> drlSet;
    private final CopyOnWriteArrayList<MapLog> mapSet;
    private final CopyOnWriteArrayList<CommittedMapLog> committedMapSet;
    private volatile byte[][] committedTempStash;
    private volatile byte[][] latestProcessingTempStash;
    private final ConcurrentLinkedQueue<ReadyEviction> readyEvictions;
    private int roundCounter;

    ConcurORAMState(int c,
                    int treeHeight,
                    int stashSize,
                    int z,
                    int s,
                    int evictInterval,
                    int blockSize) {
        this.c = c;
        this.treeHeight = treeHeight;
        this.leafCount = 1 << (treeHeight - 1);
        this.bidSpace = leafCount;
        this.stashSize = stashSize;
        this.z = z;
        this.s = s;
        this.evictInterval = c <= 1 ? 1 : Math.max(1, Math.min(evictInterval, c));
        this.bucketSize = z + s;
        this.blockSize = blockSize;
        this.maxDrlLogs = c <= 10 ? Math.max(10, c) : c;
        this.evictionCounter = 0;
        this.criticalLevels = Math.min(treeHeight, 32 - Integer.numberOfLeadingZeros(c - 1));
        this.encryptionManager = new ProtocolEncryptionManager();
        int treeSize = (1 << treeHeight) - 1;
        this.dataTree = new EncryptedORAMBucket[treeSize];
        this.wotTree = new EncryptedORAMBucket[treeSize];
        this.encryptedPositionMap = new EncryptedGlobalMapEntry[bidSpace];
        initializeEncryptedState();
        this.queryLog = new ArrayList<byte[]>();
        this.currentDrl = new byte[c][];
        this.currentMapL = new byte[c][];
        this.committedMap = new PublishedMap(-1, new byte[0][]);
        this.drlSet = new CopyOnWriteArrayList<DrlLog>();
        this.mapSet = new CopyOnWriteArrayList<MapLog>();
        this.committedMapSet = new CopyOnWriteArrayList<CommittedMapLog>();
        this.committedTempStash = new byte[stashSize][];
        this.latestProcessingTempStash = new byte[stashSize][];
        this.readyEvictions = new ConcurrentLinkedQueue<ReadyEviction>();
        this.roundCounter = 0;
    }

    private void initializeEncryptedState() {
        ORAMBucket[] plainDataTree = new ORAMBucket[dataTree.length];
        ORAMBucket[] plainWotTree = new ORAMBucket[wotTree.length];

        for (int bucketId = 0; bucketId < dataTree.length; bucketId++) {
            int level = levelOfBucket(bucketId);
            plainDataTree[bucketId] = new ORAMBucket(bucketId, bucketSize, blockSize);
            plainWotTree[bucketId] = new ORAMBucket(bucketId, bucketSize, blockSize);
        }

        for (int bid = 0; bid < bidSpace; bid++) {
            int pid = initialPidForBid(bid);
            ORAMBlock block = new ORAMBlock(bid, initialDataForBid(bid), pid, 0, false);
            SlotLocation location = placeBlockOnPathBottomUp(plainDataTree, pid, block);
            encryptedPositionMap[bid] = encryptionManager.encryptGlobalMapEntry(
                    new GlobalMapEntry(pid, location.level, location.slot, 0)
            );
        }

        for (int bucketId = 0; bucketId < plainDataTree.length; bucketId++) {
            plainWotTree[bucketId] = copyPlainBucket(plainDataTree[bucketId]);
        }

        for (int bucketId = 0; bucketId < dataTree.length; bucketId++) {
            dataTree[bucketId] = normalizeEncryptedBucket(encryptionManager.encryptBucket(plainDataTree[bucketId]));
            wotTree[bucketId] = normalizeEncryptedBucket(encryptionManager.encryptBucket(plainWotTree[bucketId]));
        }
    }

    RegisterResult registerQuery(byte[] encryptedAddr) {
        int queryId;
        int roundId;
        byte[][] prior;

        synchronized (roundLock) {
            if (queryLog.size() >= c) {
                // queryLog.clear();
                throw new IllegalStateException("Current query round is full");
            }
            queryId = queryLog.size();
            roundId = roundCounter;
            prior = queryLog.toArray(new byte[queryLog.size()][]);
            queryLog.add(encryptedAddr);
        }

        return new RegisterResult(queryId, roundId, prior);
    }

    byte[][] readDrlSetBlocks() {
        List<byte[]> out = new ArrayList<byte[]>();

        int count = 0;
        for (int i = drlSet.size() - 1; i >= 0 && count < maxDrlLogs; i--, count++) {
            for (byte[] block : drlSet.get(i).blocks) {
                out.add(block);
            }
        }

        return out.toArray(new byte[0][]);
    }

    byte[][] readCommittedMainStash() {
        return committedTempStash;
    }

    byte[][] readStashSet() {
        return new byte[0][];
    }

    byte[] readDataBlock(int pid, int[] slots) {
        int[] bucketIds = computePathBucketIds(pid);
        byte[] xor = Arrays.copyOf(
                dataTree[bucketIds[0]].getEncryptedBlocks()[slots[0]],
                dataTree[bucketIds[0]].getEncryptedBlocks()[slots[0]].length
        );

        for (int level = 1; level < bucketIds.length; level++) {
            byte[] ciphertext = dataTree[bucketIds[level]].getEncryptedBlocks()[slots[level]];
            for (int i = 0; i < xor.length; i++) {
                xor[i] ^= ciphertext[i];
            }
        }
        return xor;
    }

    byte[][] tryReadCommittedMapUpdates(int targetRoundId) {
        for (CommittedMapLog log : committedMapSet) {
            if (log.roundId == targetRoundId) {
                return Arrays.copyOf(log.entries, log.entries.length);
            }
        }

        return null;
    }

    PublishedMap tryReadCurrentMapUpdates(int targetRoundId) {
        if (roundCounter == targetRoundId) {
            for (int i = 0; i < c; i++) {
                if (currentMapL[i] == null) {
                    return null;
                }
            }

            return new PublishedMap(
                    targetRoundId,
                    Arrays.copyOf(currentMapL, currentMapL.length)
            );
        }

        if (roundCounter > targetRoundId) {
            for (MapLog log : mapSet) {
                if (log.roundId == targetRoundId) {
                    return new PublishedMap(
                            log.roundId,
                            Arrays.copyOf(log.entries, log.entries.length)
                    );
                }
            }

            return null;
        }

        throw new IllegalStateException(
                "Unexpected current map round: expected=" + targetRoundId
                        + ", actual=" + roundCounter
        );
    }

    byte[][] readCurrentDrl() {
        return Arrays.copyOf(currentDrl, currentDrl.length);
    }

    boolean isCurrentDrlPrefixReady(int queryId) {
        if (queryId < 0 || queryId >= c) {
            throw new IllegalArgumentException("Invalid query id " + queryId);
        }
        if (queryId == 0 || currentDrl[queryId - 1] != null) {
            return true;
        }
        return false;
    }

    void writeQueryResult(int queryId, byte[] encryptedBlock, byte[] encryptedMapUpdate) {
        synchronized (roundLock) {
            if (queryId < 0 || queryId >= c) {
                throw new IllegalArgumentException("Invalid query id " + queryId);
            }
            currentDrl[queryId] = encryptedBlock;
            currentMapL[queryId] = encryptedMapUpdate;
        }
    }

    private void trimOldLogs() {
        while (drlSet.size() > maxDrlLogs) {
            drlSet.remove(0);
        }

        while (mapSet.size() > maxDrlLogs) {
            mapSet.remove(0);
        }

        while (committedMapSet.size() > maxDrlLogs) {
            committedMapSet.remove(0);
        }
    }

    FinalizeResult finalizeRoundAndCommitReady() {
        byte[][] committedMapUpdates = commitReadyEvictions();

        int roundId = roundCounter++;
        byte[][] drlCopy = Arrays.copyOf(currentDrl, currentDrl.length);
        byte[][] mapCopy = Arrays.copyOf(currentMapL, currentMapL.length);

        drlSet.add(new DrlLog(roundId, drlCopy));
        mapSet.add(new MapLog(roundId, mapCopy));
        committedMapSet.add(new CommittedMapLog(roundId, committedMapUpdates));
        trimOldLogs();

        for (int i = 0; i < c; i++) {
            currentDrl[i] = null;
            currentMapL[i] = null;
        }

        queryLog.clear();
        return new FinalizeResult(roundId, committedMapUpdates);
    }

    EvictionInput readEvictionInput(int roundId, int fromQueryId, int toQueryId) {
        if (fromQueryId < 0 || toQueryId < fromQueryId || toQueryId >= c) {
            throw new IllegalArgumentException(
                    "Invalid eviction segment, roundId=" + roundId
                            + ", from=" + fromQueryId
                            + ", to=" + toQueryId
            );
        }

        byte[][] source = null;

        if (roundCounter == roundId) {
            source = currentDrl;
        } else {
            DrlLog log = findDrl(roundId);
            if (log != null) {
                source = log.blocks;
            }
        }

        // if (source == null) {
            // throw new IllegalStateException("Missing DRL for round " + roundId);
        // }

        byte[][] segment = new byte[toQueryId - fromQueryId + 1][];
        for (int i = fromQueryId; i <= toQueryId; i++) {
            if (source[i] == null) {
                throw new IllegalStateException(
                        "DRL segment not ready, roundId=" + roundId + ", queryId=" + i
                );
            }
            segment[i - fromQueryId] = source[i];
        }

        int evictionId = evictionCounter++;
        int pid = reverseLexico(evictionId);

        int[] bucketIds = computePathBucketIds(pid);
        EncryptedORAMBucket[] nonCriticalBuckets =
                new EncryptedORAMBucket[Math.max(0, bucketIds.length - criticalLevels)];

        for (int level = criticalLevels; level < bucketIds.length; level++) {
            nonCriticalBuckets[level - criticalLevels] = copyBucket(wotTree[bucketIds[level]]);
        }

        return new EvictionInput(
                evictionId,
                roundId,
                pid,
                segment,
                nonCriticalBuckets
        );
    }

    EvictionCriticalInput readEvictionCritical(int evictionId) {
        int pid = reverseLexico(evictionId);
        int[] bucketIds = computePathBucketIds(pid);

        EncryptedORAMBucket[] criticalBuckets =
                new EncryptedORAMBucket[Math.min(criticalLevels, bucketIds.length)];

        for (int level = 0; level < criticalBuckets.length; level++) {
            criticalBuckets[level] = copyBucket(wotTree[bucketIds[level]]);
        }

        return new EvictionCriticalInput(
                Arrays.copyOf(latestProcessingTempStash, latestProcessingTempStash.length),
                criticalBuckets
        );
    }

    void submitEviction(
            int evictionId,
            EncryptedORAMPath encryptedPath,
            byte[][] tempStashBlocks,
            byte[][] mapUpdates
    ) {
        writePathTo(wotTree, encryptedPath, 0, treeHeight);

        byte[][] tempCopy = normalizeStashBlocks(tempStashBlocks);
        byte[][] mapCopy = mapUpdates == null ? new byte[0][] : Arrays.copyOf(mapUpdates, mapUpdates.length);

        latestProcessingTempStash = tempCopy;
        readyEvictions.add(new ReadyEviction(
                evictionId,
                encryptedPath.getPid(),
                tempCopy,
                mapCopy
        ));
    }

    private byte[][] commitReadyEvictions() {
        List<byte[]> committedMapUpdates = new ArrayList<byte[]>();

        ReadyEviction ready;
        while ((ready = readyEvictions.poll()) != null) {
            copyPath(wotTree, dataTree, ready.pathId);
            committedTempStash = ready.tempStashBlocks;

            for (byte[] entry : ready.mapUpdates) {
                committedMapUpdates.add(entry);
            }

            // Segment eviction: do not remove whole round logs here.
            // removeDrl(...)
            // removeMap(...)
        }

        return committedMapUpdates.toArray(new byte[0][]);
    }

    private DrlLog findDrl(int roundId) {
        for (DrlLog log : drlSet) {
            if (log.roundId == roundId) {
                return log;
            }
        }
        return null;
    }

    private void removeDrl(int roundId) {
        drlSet.removeIf(log -> log.roundId == roundId);
    }

    private void removeMap(int roundId) {
        mapSet.removeIf(log -> log.roundId == roundId);
    }

    private EncryptedORAMPath readPathFrom(EncryptedORAMBucket[] tree, int pid) {
        validatePid(pid);
        int[] bucketIds = computePathBucketIds(pid);
        EncryptedORAMBucket[] buckets = new EncryptedORAMBucket[bucketIds.length];
        for (int i = 0; i < bucketIds.length; i++) {
            buckets[i] = copyBucket(tree[bucketIds[i]]);
        }
        return new EncryptedORAMPath(pid, buckets);
    }

    private void writePathTo(EncryptedORAMBucket[] tree, EncryptedORAMPath path, int levelStartInclusive, int levelEndExclusive) {
        int[] bucketIds = computePathBucketIds(path.getPid());
        EncryptedORAMBucket[] buckets = path.getBuckets();
        if (bucketIds.length != buckets.length) {
            throw new IllegalArgumentException("Path length mismatch");
        }
        int start = Math.max(0, levelStartInclusive);
        int end = Math.min(bucketIds.length, levelEndExclusive);
        for (int i = start; i < end; i++) {
            tree[bucketIds[i]] = normalizeEncryptedBucket(buckets[i]);
        }
    }

    private void copyPath(EncryptedORAMBucket[] src, EncryptedORAMBucket[] dst, int pid) {
        int[] bucketIds = computePathBucketIds(pid);
        for (int bucketId : bucketIds) {
            dst[bucketId] = src[bucketId];
        }
    }

    private int[] computePathBucketIds(int pid) {
        int[] bucketIds = new int[treeHeight];
        int index = 0;
        for (int level = 0; level < treeHeight; level++) {
            bucketIds[level] = index;
            if (level < treeHeight - 1) {
                int bit = (pid >> ((treeHeight - 2) - level)) & 1;
                index = 2 * index + 1 + bit;
            }
        }
        return bucketIds;
    }

    private void validatePid(int pid) {
        if (pid < 0 || pid >= leafCount) {
            throw new IllegalArgumentException("Invalid pid " + pid);
        }
    }

    private int levelOfBucket(int bucketId) {
        return 31 - Integer.numberOfLeadingZeros(bucketId + 1);
    }

    private int reverseLexico(int counter) {
        return Integer.reverse(counter & (leafCount - 1)) >>> (Integer.SIZE - (treeHeight - 1));
    }

    private EncryptedORAMBucket copyBucket(EncryptedORAMBucket bucket) {
        return new EncryptedORAMBucket(bucket.getBucketId(), Arrays.copyOf(bucket.getEncryptedBlocks(), bucket.getEncryptedBlocks().length));
    }

    private byte[][] normalizeStashBlocks(byte[][] stashBlocks) {
        byte[][] normalized = new byte[stashSize][];
        if (stashBlocks == null) {
            return normalized;
        }
        System.arraycopy(stashBlocks, 0, normalized, 0, Math.min(stashBlocks.length, normalized.length));
        return normalized;
    }

    private EncryptedORAMBucket normalizeEncryptedBucket(EncryptedORAMBucket bucket) {
        byte[][] blocks = Arrays.copyOf(bucket.getEncryptedBlocks(), bucket.getEncryptedBlocks().length);
        for (int i = z; i < blocks.length; i++) {
            int length = blocks[i] == null ? 0 : blocks[i].length;
            blocks[i] = new byte[length];
        }
        return new EncryptedORAMBucket(bucket.getBucketId(), blocks);
    }

    private ORAMBucket copyPlainBucket(ORAMBucket bucket) {
        ORAMBlock[] copiedBlocks = new ORAMBlock[bucket.size()];
        for (int i = 0; i < bucket.size(); i++) {
            copiedBlocks[i] = copyPlainBlock(bucket.getBlock(i));
        }
        return new ORAMBucket(bucket.getBucketId(), copiedBlocks);
    }

    private ORAMBlock copyPlainBlock(ORAMBlock block) {
        return new ORAMBlock(
                block.getBid(),
                Arrays.copyOf(block.getData(), block.getData().length),
                block.getPid(),
                block.getSeq(),
                block.isDummy()
        );
    }


    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for DRLSet capacity", e);
        }
    }

    private void sleepQuietly(Object lock, long millis) {
        try {
            lock.wait(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for eviction session", e);
        }
    }

    private byte[] initialDataForBid(int bid) {
        byte[] data = new byte[blockSize];
        byte[] source = ("block-" + bid).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int copyLength = Math.min(source.length, data.length);
        System.arraycopy(source, 0, data, 0, copyLength);
        return data;
    }

    private int initialPidForBid(int bid) {
        return Integer.reverse(bid & (leafCount - 1)) >>> (Integer.SIZE - (treeHeight - 1));
    }

    private SlotLocation placeBlockOnPathBottomUp(ORAMBucket[] plainTree, int pid, ORAMBlock block) {
        int[] bucketIds = computePathBucketIds(pid);
        for (int i = bucketIds.length - 1; i >= 0; i--) {
            ORAMBucket bucket = plainTree[bucketIds[i]];
            int slot = tryPutBlockInFirstRealSlot(bucket, block);
            if (slot >= 0) {
                return new SlotLocation(i, slot);
            }
        }
        throw new IllegalStateException("No free slot on path for bid " + block.getBid() + ", pid=" + pid);
    }

    private int tryPutBlockInFirstRealSlot(ORAMBucket bucket, ORAMBlock block) {
        ORAMBlock[] blocks = bucket.getBlocks();
        for (int i = 0; i < z; i++) {
            if (blocks[i].isDummy()) {
                blocks[i] = block;
                return i;
            }
        }
        return -1;
    }

    static class RegisterResult {
        private final int queryId;
        private final int roundId;
        private final byte[][] priorQl;

        RegisterResult(int queryId, int roundId, byte[][] priorQl) {
            this.queryId = queryId;
            this.roundId = roundId;
            this.priorQl = priorQl;
        }

        int getQueryId() {
            return queryId;
        }

        int getRoundId() {
            return roundId;
        }

        byte[][] getPriorQl() {
            return priorQl;
        }
    }

    static class PublishedMap {
        private final int roundId;
        private final byte[][] entries;

        PublishedMap(int roundId, byte[][] entries) {
            this.roundId = roundId;
            this.entries = entries;
        }

        int getRoundId() {
            return roundId;
        }

        byte[][] getEntries() {
            return entries;
        }
    }

    static class FinalizeResult {
        private final int roundId;
        private final byte[][] committedMapUpdates;

        FinalizeResult(int roundId, byte[][] committedMapUpdates) {
            this.roundId = roundId;
            this.committedMapUpdates = committedMapUpdates;
        }

        int getRoundId() {
            return roundId;
        }

        byte[][] getCommittedMapUpdates() {
            return committedMapUpdates;
        }
    }

    static class EvictionInput {
        private final int evictionId;
        private final int roundId;
        private final int pid;
        private final byte[][] drlBlocks;
        private final EncryptedORAMBucket[] nonCriticalBuckets;

        EvictionInput(
                int evictionId,
                int roundId,
                int pid,
                byte[][] drlBlocks,
                EncryptedORAMBucket[] nonCriticalBuckets
        ) {
            this.evictionId = evictionId;
            this.roundId = roundId;
            this.pid = pid;
            this.drlBlocks = drlBlocks;
            this.nonCriticalBuckets = nonCriticalBuckets;
        }

        int getEvictionId() {
            return evictionId;
        }

        int getRoundId() {
            return roundId;
        }

        int getPid() {
            return pid;
        }

        byte[][] getDrlBlocks() {
            return drlBlocks;
        }

        EncryptedORAMBucket[] getNonCriticalBuckets() {
            return nonCriticalBuckets;
        }
    }

    static class EvictionCriticalInput {
        private final byte[][] tempStashBlocks;
        private final EncryptedORAMBucket[] criticalBuckets;

        EvictionCriticalInput(byte[][] tempStashBlocks, EncryptedORAMBucket[] criticalBuckets) {
            this.tempStashBlocks = tempStashBlocks;
            this.criticalBuckets = criticalBuckets;
        }

        byte[][] getTempStashBlocks() {
            return tempStashBlocks;
        }

        EncryptedORAMBucket[] getCriticalBuckets() {
            return criticalBuckets;
        }
    }

    private static class MapLog {
        private final int roundId;
        private final byte[][] entries;

        MapLog(int roundId, byte[][] entries) {
            this.roundId = roundId;
            this.entries = entries;
        }
    }

    private static class DrlLog {
        private final int roundId;
        private final byte[][] blocks;

        DrlLog(int roundId, byte[][] blocks) {
            this.roundId = roundId;
            this.blocks = blocks;
        }
    }

    private static class CommittedMapLog {
        private final int roundId;
        private final byte[][] entries;

        CommittedMapLog(int roundId, byte[][] entries) {
            this.roundId = roundId;
            this.entries = entries;
        }
    }

    private static class EvictionTask {
        private final int roundId;
        private final int fromQueryId;
        private final int toQueryId;

        private EvictionTask(int roundId, int fromQueryId, int toQueryId) {
            this.roundId = roundId;
            this.fromQueryId = fromQueryId;
            this.toQueryId = toQueryId;
        }
    }

    private static class ReadyEviction {
        private final int evictionId;
        private final int pathId;
        private final byte[][] tempStashBlocks;
        private final byte[][] mapUpdates;

        ReadyEviction(int evictionId, int pathId, byte[][] tempStashBlocks, byte[][] mapUpdates) {
            this.evictionId = evictionId;
            this.pathId = pathId;
            this.tempStashBlocks = tempStashBlocks;
            this.mapUpdates = mapUpdates;
        }
    }

    private static class SlotLocation {
        private final int level;
        private final int slot;

        private SlotLocation(int level, int slot) {
            this.level = level;
            this.slot = slot;
        }
    }
}
