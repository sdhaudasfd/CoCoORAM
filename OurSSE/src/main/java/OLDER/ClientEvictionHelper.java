package oram.client;

import oram.structure.GlobalMapEntry;
import oram.structure.ORAMBlock;
import oram.structure.ORAMBucket;
import oram.structure.ORAMPath;
import oram.structure.RLSlot;
import oram.structure.UpdateMapEntry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ClientEvictionHelper {
    private final int treeHeight;
    private final int blockSize;
    private final int c;
    private final int leafCount;

    public ClientEvictionHelper(int treeHeight, int blockSize, int c, int leafCount) {
        this.treeHeight = treeHeight;
        this.blockSize = blockSize;
        this.c = c;
        this.leafCount = leafCount;
    }

    public BuildEvictionResult buildEviction(ORAMPath pathE,
                                             RLSlot[] lastRL,
                                             UpdateMapEntry[] lastUdMp,
                                             Map<Integer, GlobalMapEntry> gbMp,
                                             EvictionAssignment assignment) {
        Map<Integer, LatestPointer> latestPointers = buildLatestPointers(pathE, lastRL, lastUdMp, gbMp, assignment);
        ORAMBucket[] rebuiltBuckets = rebuildOwnedBuckets(pathE, lastRL, assignment, latestPointers);
        ensureOwnedPathLatestBlocksPlaced(pathE, rebuiltBuckets, assignment, latestPointers);
        Map<Integer, GlobalMapEntry> mpPrime = buildMpPrime(latestPointers, assignment);

        return new BuildEvictionResult(new ORAMPath(pathE.getPid(), rebuiltBuckets), mpPrime);
    }

    private Map<Integer, LatestPointer> buildLatestPointers(ORAMPath pathE,
                                                            RLSlot[] lastRL,
                                                            UpdateMapEntry[] lastUdMp,
                                                            Map<Integer, GlobalMapEntry> gbMp,
                                                            EvictionAssignment assignment) {
        Set<Integer> relevantBids = new HashSet<>();

        for (int bid = assignment.getBidStartInclusive(); bid < assignment.getBidEndExclusive(); bid++) {
            relevantBids.add(bid);
        }

        for (ORAMBlock block : collectPathBlocks(pathE)) {
            relevantBids.add(block.getBid());
        }

        for (ORAMBlock block : collectLastRlBlocks(lastRL)) {
            relevantBids.add(block.getBid());
        }

        Map<Integer, LatestPointer> latestPointers = new HashMap<>();
        for (int bid : relevantBids) {
            LatestPointer latest = latestFromGbMp(gbMp.get(bid));

            for (UpdateMapEntry entry : lastUdMp) {
                if (entry == null || entry.isDummy() || entry.getBid() != bid) {
                    continue;
                }
                latest = chooseNewer(latest, entry.getPid(), entry.getSeq());
            }

            if (latest != null) {
                latestPointers.put(bid, latest);
            }
        }

        return latestPointers;
    }

    private ORAMBucket[] rebuildOwnedBuckets(ORAMPath pathE,
                                             RLSlot[] lastRL,
                                             EvictionAssignment assignment,
                                             Map<Integer, LatestPointer> latestPointers) {
        ORAMBucket[] rebuilt = copyPathBuckets(pathE);
        for (int level : assignment.getOwnedBucketIndexes()) {
            ORAMBucket original = pathE.getBucket(level);
            rebuilt[level] = new ORAMBucket(original.getBucketId(), original.size(), blockSize);
        }

        List<ORAMBlock> survivingPathBlocks =
                collectSurvivingOwnedPathBlocks(pathE, assignment, latestPointers);
        placePathBlocksDeepestFirst(rebuilt, pathE.getPid(), assignment.getOwnedBucketIndexes(), survivingPathBlocks);

        List<ORAMBlock> ownedLatestRlBlocks = collectOwnedLatestLastRlBlocks(lastRL, assignment, latestPointers);
        ownedLatestRlBlocks.sort(Comparator
                .comparingInt(ORAMBlock::getSeq).reversed()
                .thenComparingInt(ORAMBlock::getBid));

        List<Integer> orderedOwnedLevels = toDescendingLevels(assignment.getOwnedBucketIndexes());
        for (ORAMBlock block : ownedLatestRlBlocks) {
            if (existsOnPath(rebuilt, block.getBid(), block.getSeq())) {
                continue;
            }

            boolean placed = false;
            for (int level : orderedOwnedLevels) {
                if (!canPlaceOnLevel(block.getPid(), pathE.getPid(), level)) {
                    continue;
                }

                if (tryPutBlockInFirstDummySlot(rebuilt[level], block)) {
                    placed = true;
                    break;
                }
            }

            if (!placed) {
                throw new IllegalStateException(
                        "Eviction overflow on pid_e=" + pathE.getPid() +
                                ", block=(bid=" + block.getBid() +
                                ",pid=" + block.getPid() +
                                ",seq=" + block.getSeq() + ")"
                );
            }
        }

        return rebuilt;
    }

    private void ensureOwnedPathLatestBlocksPlaced(ORAMPath pathE,
                                                   ORAMBucket[] rebuiltBuckets,
                                                   EvictionAssignment assignment,
                                                   Map<Integer, LatestPointer> latestPointers) {
        List<ORAMBlock> survivingPathBlocks =
                collectSurvivingOwnedPathBlocks(pathE, assignment, latestPointers);

        for (ORAMBlock block : survivingPathBlocks) {
            if (!existsOnPath(rebuiltBuckets, block.getBid(), block.getSeq())) {
                throw new IllegalStateException(
                        "Owned path block lost during eviction, bid=" + block.getBid() +
                                ", seq=" + block.getSeq() +
                                ", pid_e=" + pathE.getPid()
                );
            }
        }
    }

    private Map<Integer, GlobalMapEntry> buildMpPrime(Map<Integer, LatestPointer> latestPointers,
                                                      EvictionAssignment assignment) {
        Map<Integer, GlobalMapEntry> mpPrime = new HashMap<>();

        for (int bid = assignment.getBidStartInclusive(); bid < assignment.getBidEndExclusive(); bid++) {
            LatestPointer latest = latestPointers.get(bid);
            if (latest != null) {
                mpPrime.put(bid, new GlobalMapEntry(latest.pid, latest.seq));
            }
        }

        return mpPrime;
    }

    private List<ORAMBlock> collectSurvivingOwnedPathBlocks(ORAMPath pathE,
                                                            EvictionAssignment assignment,
                                                            Map<Integer, LatestPointer> latestPointers) {
        List<ORAMBlock> result = new ArrayList<>();

        for (int level : assignment.getOwnedBucketIndexes()) {
            ORAMBucket bucket = pathE.getBucket(level);
            for (ORAMBlock block : bucket.getBlocks()) {
                if (block == null || block.isDummy()) {
                    continue;
                }

                LatestPointer latest = latestPointers.get(block.getBid());
                if (latest == null) {
                    continue;
                }

                if (block.getSeq() == latest.seq && block.getPid() == latest.pid) {
                    result.add(copyBlock(block));
                }
            }
        }

        result.sort(Comparator
                .comparingInt(ORAMBlock::getSeq).reversed()
                .thenComparingInt(ORAMBlock::getBid));
        return result;
    }

    private List<ORAMBlock> collectOwnedLatestLastRlBlocks(RLSlot[] lastRL,
                                                           EvictionAssignment assignment,
                                                           Map<Integer, LatestPointer> latestPointers) {
        List<ORAMBlock> result = new ArrayList<>();

        for (ORAMBlock block : collectLastRlBlocks(lastRL)) {
            LatestPointer latest = latestPointers.get(block.getBid());
            if (latest == null) {
                continue;
            }

            if (block.getSeq() != latest.seq || block.getPid() != latest.pid) {
                continue;
            }

            if (findLastRlOwner(assignment, block) == assignment.getSlot()) {
                result.add(copyBlock(block));
            }
        }

        return result;
    }

    private int findLastRlOwner(EvictionAssignment assignment, ORAMBlock block) {
        int timestepBaseSeq = assignment.getSeq() - assignment.getSlot();
        int bestSlot = -1;
        int bestCount = -1;
        int bestDeepestLevel = -1;

        for (int slot = 0; slot < c; slot++) {
            int seq = timestepBaseSeq + slot;
            int pidE = digitReverse(seq % leafCount, treeHeight - 1);
            int[] ownedLevels = computeOwnedBucketIndexes(timestepBaseSeq, slot, pidE);

            int count = 0;
            int deepestLevel = -1;
            for (int level : ownedLevels) {
                if (canPlaceOnLevel(block.getPid(), pidE, level)) {
                    count++;
                    deepestLevel = Math.max(deepestLevel, level);
                }
            }

            if (count > bestCount || (count == bestCount && deepestLevel > bestDeepestLevel)) {
                bestSlot = slot;
                bestCount = count;
                bestDeepestLevel = deepestLevel;
            }
        }

        return bestSlot;
    }

    private void placePathBlocksDeepestFirst(ORAMBucket[] rebuilt,
                                             int pathPid,
                                             int[] ownedLevels,
                                             List<ORAMBlock> pathBlocks) {
        List<Integer> orderedOwnedLevels = toDescendingLevels(ownedLevels);

        for (ORAMBlock block : pathBlocks) {
            boolean placed = false;
            for (int level : orderedOwnedLevels) {
                if (!canPlaceOnLevel(block.getPid(), pathPid, level)) {
                    continue;
                }

                if (tryPutBlockInFirstDummySlot(rebuilt[level], block)) {
                    placed = true;
                    break;
                }
            }

            if (!placed) {
                throw new IllegalStateException(
                        "Failed to place surviving path block, bid=" + block.getBid() +
                                ", seq=" + block.getSeq() +
                                ", pid_e=" + pathPid
                );
            }
        }
    }

    private List<ORAMBlock> collectPathBlocks(ORAMPath pathE) {
        List<ORAMBlock> result = new ArrayList<>();

        for (ORAMBucket bucket : pathE.getBuckets()) {
            for (ORAMBlock block : bucket.getBlocks()) {
                if (block == null || block.isDummy()) {
                    continue;
                }
                result.add(copyBlock(block));
            }
        }

        return result;
    }

    private List<ORAMBlock> collectLastRlBlocks(RLSlot[] lastRL) {
        List<ORAMBlock> result = new ArrayList<>();

        for (RLSlot rlSlot : lastRL) {
            if (rlSlot == null || rlSlot.isDummy()) {
                continue;
            }

            result.add(new ORAMBlock(
                    rlSlot.getBid(),
                    copyData(rlSlot.getData()),
                    rlSlot.getPid(),
                    rlSlot.getSeq(),
                    false
            ));
        }

        return result;
    }

    private List<Integer> toDescendingLevels(int[] levels) {
        List<Integer> ordered = new ArrayList<>();
        for (int level : levels) {
            ordered.add(level);
        }
        ordered.sort(Comparator.reverseOrder());
        return ordered;
    }

    private LatestPointer latestFromGbMp(GlobalMapEntry entry) {
        if (entry == null) {
            return null;
        }
        return new LatestPointer(entry.getPid(), entry.getSeq());
    }

    private LatestPointer chooseNewer(LatestPointer current, int pid, int seq) {
        if (current == null || seq > current.seq) {
            return new LatestPointer(pid, seq);
        }
        return current;
    }

    private int[] computeOwnedBucketIndexes(int timestepBaseSeq, int mySlot, int myPidE) {
        boolean[] owned = new boolean[treeHeight];

        for (int level = 0; level < treeHeight; level++) {
            owned[level] = isOwnerForLevel(timestepBaseSeq, mySlot, myPidE, level);
        }

        int count = 0;
        for (boolean value : owned) {
            if (value) {
                count++;
            }
        }

        int[] indexes = new int[count];
        int idx = 0;
        for (int level = 0; level < treeHeight; level++) {
            if (owned[level]) {
                indexes[idx++] = level;
            }
        }

        return indexes;
    }

    private boolean isOwnerForLevel(int timestepBaseSeq, int mySlot, int myPidE, int level) {
        int myPrefix = prefixAtLevel(myPidE, level);

        for (int otherSlot = 0; otherSlot < c; otherSlot++) {
            int otherSeq = timestepBaseSeq + otherSlot;
            int otherPidE = digitReverse(otherSeq % leafCount, treeHeight - 1);
            int otherPrefix = prefixAtLevel(otherPidE, level);

            if (otherPrefix == myPrefix && otherSlot < mySlot) {
                return false;
            }
        }

        return true;
    }

    private int prefixAtLevel(int pid, int level) {
        int shift = (treeHeight - 1) - level;
        return pid >> shift;
    }

    private int digitReverse(int value, int bits) {
        int result = 0;
        for (int i = 0; i < bits; i++) {
            result <<= 1;
            result |= (value >> i) & 1;
        }
        return result;
    }

    private boolean canPlaceOnLevel(int targetPid, int pathPid, int level) {
        int shift = (treeHeight - 1) - level;
        return (targetPid >> shift) == (pathPid >> shift);
    }

    private boolean existsOnPath(ORAMBucket[] buckets, int bid, int seq) {
        for (ORAMBucket bucket : buckets) {
            for (ORAMBlock block : bucket.getBlocks()) {
                if (block == null || block.isDummy()) {
                    continue;
                }
                if (block.getBid() == bid && block.getSeq() == seq) {
                    return true;
                }
            }
        }
        return false;
    }

    private ORAMBucket[] copyPathBuckets(ORAMPath path) {
        ORAMBucket[] copy = new ORAMBucket[path.length()];

        for (int i = 0; i < path.length(); i++) {
            copy[i] = copyBucket(path.getBucket(i));
        }

        return copy;
    }

    private ORAMBucket copyBucket(ORAMBucket bucket) {
        ORAMBlock[] source = bucket.getBlocks();
        ORAMBlock[] copy = new ORAMBlock[source.length];

        for (int i = 0; i < source.length; i++) {
            copy[i] = copyBlock(source[i]);
        }

        return new ORAMBucket(bucket.getBucketId(), copy);
    }

    private ORAMBlock copyBlock(ORAMBlock block) {
        return new ORAMBlock(
                block.getBid(),
                copyData(block.getData()),
                block.getPid(),
                block.getSeq(),
                block.isDummy()
        );
    }

    private byte[] copyData(byte[] source) {
        return Arrays.copyOf(source, source.length);
    }

    private boolean tryPutBlockInFirstDummySlot(ORAMBucket bucket, ORAMBlock block) {
        for (int i = 0; i < bucket.size(); i++) {
            if (bucket.getBlock(i).isDummy()) {
                bucket.setBlock(i, copyBlock(block));
                return true;
            }
        }
        return false;
    }

    private static class LatestPointer {
        private final int pid;
        private final int seq;

        private LatestPointer(int pid, int seq) {
            this.pid = pid;
            this.seq = seq;
        }
    }
}
