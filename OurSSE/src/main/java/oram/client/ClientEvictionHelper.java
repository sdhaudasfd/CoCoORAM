package oram.client;

import oram.structure.GlobalMapEntry;
import oram.structure.ORAMBlock;
import oram.structure.ORAMBucket;
import oram.structure.ORAMPath;
import oram.structure.RLSlot;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ClientEvictionHelper {
    private final int treeHeight;
    private final int rootBucketSize;
    private final int competitionBucketSize;
    private final int bucketSize;
    private final int blockSize;
    private final int c;
    private final int leafCount;

    public ClientEvictionHelper(int treeHeight,
                                int rootBucketSize,
                                int competitionBucketSize,
                                int bucketSize,
                                int blockSize,
                                int c,
                                int leafCount) {
        this.treeHeight = treeHeight;
        this.rootBucketSize = rootBucketSize;
        this.competitionBucketSize = competitionBucketSize;
        this.bucketSize = bucketSize;
        this.blockSize = blockSize;
        this.c = c;
        this.leafCount = leafCount;
    }

    public BuildEvictionResult buildEviction(ORAMPath pathE,
                                             RLSlot[] lastRL,
                                             GlobalMapEntry[] gbMp,
                                             EvictionAssignment assignment) {
        ORAMBucket[] rebuiltBuckets = buildBuckets(pathE, lastRL, gbMp, assignment);
        return new BuildEvictionResult(new ORAMPath(pathE.getPid(), rebuiltBuckets));
    }

    private ORAMBucket[] buildBuckets(ORAMPath pathE,
                                      RLSlot[] lastRL,
                                      GlobalMapEntry[] gbMp,
                                      EvictionAssignment assignment) {
        ORAMBucket[] rebuilt = new ORAMBucket[pathE.length()];
        for (int level = 0; level < pathE.length(); level++) {
            rebuilt[level] = emptyBucketLike(pathE.getBucket(level));
        }

        if (pathE.length() > 0 && pathE.getBucket(0).isWeighted()) {
            placeWeightedCandidates(rebuilt, pathE, lastRL, gbMp, assignment);
            return rebuilt;
        }

        for (ORAMBucket bucket : pathE.getBuckets()) {
            for (ORAMBlock block : bucket.getBlocks()) {
                if (!isLatestAndOwned(block, gbMp, assignment)) {
                    continue;
                }
                placeBlockOrThrow(rebuilt, pathE, assignment, block);
            }
        }

        for (RLSlot rlSlot : lastRL) {
            if (!isLatestAndOwned(rlSlot, gbMp, assignment)) {
                continue;
            }
            placeBlockOrThrow(rebuilt, pathE, assignment, rlSlot);
        }

        return rebuilt;
    }

    private void placeWeightedCandidates(ORAMBucket[] rebuilt,
                                         ORAMPath pathE,
                                         RLSlot[] lastRL,
                                         GlobalMapEntry[] gbMp,
                                         EvictionAssignment assignment) {
        Map<Integer, ORAMBlock> candidateByBid = new LinkedHashMap<>();
        for (ORAMBucket bucket : pathE.getBuckets()) {
            for (ORAMBlock block : bucket.getBlocks()) {
                if (isLatestAndOwned(block, gbMp, assignment)) {
                    candidateByBid.put(block.getBid(), block);
                }
            }
        }
        for (RLSlot slot : lastRL) {
            if (isLatestAndOwned(slot, gbMp, assignment)) {
                candidateByBid.put(slot.getBid(), slot);
            }
        }

        List<ORAMBlock> candidates = new ArrayList<>(candidateByBid.values());
        candidates.sort(
                Comparator.comparingInt(
                                (ORAMBlock block) -> deepestCommonLevel(block.getPid(), pathE.getPid())
                        )
                        .thenComparing(
                                Comparator.comparingInt(
                                        (ORAMBlock block) -> block.getData().length
                                ).reversed()
                        )
                        .thenComparingInt(ORAMBlock::getBid)
        );

        for (ORAMBlock block : candidates) {
            placeBlockOrThrow(rebuilt, pathE, assignment, block);
        }
    }

    private ORAMBucket emptyBucketLike(ORAMBucket bucket) {
        if (bucket.isWeighted()) {
            int level = levelOfBucket(bucket.getBucketId());
            int multiplier = isCompetitionLevel(level)
                    ? segmentBaseSize(level)
                    : bucketSize;
            return ORAMBucket.weighted(
                    bucket.getBucketId(),
                    multiplier * blockSize,
                    new ORAMBlock[0]
            );
        }
        ORAMBlock[] blocks = new ORAMBlock[bucket.size()];
        for (int i = 0; i < blocks.length; i++) {
            blocks[i] = ORAMBlock.dummy(blockSize);
        }
        return new ORAMBucket(bucket.getBucketId(), blocks);
    }

    private boolean isLatestAndOwned(ORAMBlock block,
                                     GlobalMapEntry[] gbMp,
                                     EvictionAssignment assignment) {
        // Latest: check if the block is the latest version according to gbMp
        if (block == null || block.isDummy()) {
            return false;
        }
        if (block.getBid() < 0 || block.getBid() >= gbMp.length) {
            return false;
        }
        GlobalMapEntry latest = gbMp[block.getBid()];
        if (latest == null) {
            return false;
        }
        if (block.getSeq() != latest.getSeq() || block.getPid() != latest.getPid()) {
            return false;
        }

        // Owned: check if the block is owned by the client
        int seq = assignment.getSeq();
        int blockPid = block.getPid();
        int currentSlot = seq % c;
        int timestepBaseSeq = seq - currentSlot;

        // If c = 4，maxCompetitionLevel=2
        int maxCompetitionLevel = 31 - Integer.numberOfLeadingZeros(c);
        int shift = (treeHeight - 1) - maxCompetitionLevel;
        int seqPid = digitReverse(seq % leafCount, treeHeight - 1);
        // Whether same prefix
        boolean samePrefix = (seqPid >> shift) == (blockPid >> shift);
        if (!samePrefix) {
            return false;
        }
        if (shift == 0) {
            return true;
        }
        //Whether same child
        boolean sameChild = (seqPid >> (shift - 1)) == (blockPid >> (shift - 1));
        if (sameChild) {
            return true;
        }

        int blockChildPrefix = blockPid >> (shift - 1);

        for (int otherSlot = 0; otherSlot < c; otherSlot++) {
            if (otherSlot == currentSlot) {
                continue;
            }

            int otherSeq = timestepBaseSeq + otherSlot;
            int otherPid = digitReverse(otherSeq % leafCount, treeHeight - 1);

            if ((otherPid >> (shift - 1)) == blockChildPrefix) {
                return false;
            }
        }

        return true;
    }

    private int digitReverse(int value, int bits) {
        return Integer.reverse(value) >>> (32 - bits);
    }

    private void placeBlockOrThrow(ORAMBucket[] rebuilt,
                                   ORAMPath pathE,
                                   EvictionAssignment assignment,
                                   ORAMBlock block) {
        int deepestLevel = deepestCommonLevel(block.getPid(), pathE.getPid());
        for (int level = deepestLevel; level >= 0; level--) {
            if (tryPutBlockInOwnSegment(rebuilt[level], block, pathE.getPid(), assignment, level)) {
                return;
            }
        }

        throw new IllegalStateException(
                buildOverflowMessage(pathE, assignment, rebuilt, block, deepestLevel)
        );
    }

    private int deepestCommonLevel(int a, int b) {
        int x = a ^ b;
        return (treeHeight - 2) - (31 - Integer.numberOfLeadingZeros(x));
    }

    private boolean tryPutBlockInOwnSegment(ORAMBucket bucket,
                                            ORAMBlock block,
                                            int pathPid,
                                            EvictionAssignment assignment,
                                            int level) {
        if (bucket.isWeighted()) {
            int weight = block.getData().length;
            if (bucket.getUsedBytes() + weight > bucket.getCapacityBytes()) {
                return false;
            }
            ORAMBlock[] current = bucket.getBlocks();
            ORAMBlock[] expanded = Arrays.copyOf(current, current.length + 1);
            expanded[current.length] = copyBlock(block);
            bucket.setBlocks(expanded);
            return true;
        }
        int start = 0;
        int end = bucket.size();

        if (isCompetitionLevel(level)) {
            int size = segmentBaseSize(level);
            int seq = assignment.getSeq();
            int slot = assignment.getSlot();
            int timestepBaseSeq = seq - slot;

            int shift = (treeHeight - 1) - level;
            int myPrefix = pathPid >> shift;
            int rank = 0;

            for (int otherSlot = 0; otherSlot < slot; otherSlot++) {
                int otherPid = digitReverse((timestepBaseSeq + otherSlot) & (leafCount - 1), treeHeight - 1);
                if ((otherPid >> shift) == myPrefix) {
                    rank++;
                }
            }

            start = rank * size;
            end = start + size;
        }

        for (int i = start; i < end; i++) {
            if (bucket.getBlock(i).isDummy()) {
                bucket.setBlock(i, copyBlock(block));
                return true;
            }
        }

        return false;
    }

    private boolean isCompetitionLevel(int level) {
        int groups = 1 << level;
        int contenders = groups >= c ? 1 : (c + groups - 1) / groups;
        return contenders > 1;
    }

    private int segmentBaseSize(int level) {
        return level == 0 ? rootBucketSize : competitionBucketSize;
    }

    private int levelOfBucket(int bucketId) {
        return 31 - Integer.numberOfLeadingZeros(bucketId + 1);
    }

    private ORAMBlock copyBlock(ORAMBlock block) {
        return new ORAMBlock(
                block.getBid(),
                Arrays.copyOf(block.getData(), block.getData().length),
                block.getPid(),
                block.getSeq(),
                block.isDummy()
        );
    }

    private String buildOverflowMessage(ORAMPath pathE,
                                        EvictionAssignment assignment,
                                        ORAMBucket[] rebuilt,
                                        ORAMBlock block,
                                        int deepestLevel) {
        StringBuilder builder = new StringBuilder();
        builder.append("Eviction overflow on pid_e=").append(pathE.getPid())
                .append(", slot=").append(assignment.getSlot())
                .append(", seq=").append(assignment.getSeq())
                .append(", block=(bid=").append(block.getBid())
                .append(",pid=").append(block.getPid())
                .append(",seq=").append(block.getSeq())
                .append(")")
                .append(", deepestCommonLevel=").append(deepestLevel);

        return builder.toString();
    }
}

class BuildEvictionResult {
    private final ORAMPath pathPrime;

    BuildEvictionResult(ORAMPath pathPrime) {
        this.pathPrime = pathPrime;
    }

    ORAMPath getPathPrime() {
        return pathPrime;
    }
}
