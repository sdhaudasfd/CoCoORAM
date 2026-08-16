package oram.client;

import oram.security.WeightedEncryptionManager;
import oram.sse.SSEChunk;
import oram.sse.SSEChunkCodec;
import oram.structure.EncryptedMapUpdate;
import oram.structure.ORAMBlock;
import oram.structure.ORAMBucket;
import oram.structure.ORAMPath;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class BlockSSEAccessExecutor {
    private final StrongMVPManager manager;
    private final int leafCount;
    private final int treeHeight;
    private final int rootBucketSize;
    private final int bucketSize;
    private final int blockSize;
    private final WeightedEncryptionManager encryptionManager = new WeightedEncryptionManager();
    private final SecureRandom random = new SecureRandom();
    private final Map<Integer, ORAMBlock> sharedStash;

    public BlockSSEAccessExecutor(StrongMVPManager manager,
                                  int leafCount,
                                  int treeHeight,
                                  int rootBucketSize,
                                  int bucketSize,
                                  int blockSize,
                                  Map<Integer, ORAMBlock> sharedStash) {
        this.manager = manager;
        this.leafCount = leafCount;
        this.treeHeight = treeHeight;
        this.rootBucketSize = rootBucketSize;
        this.bucketSize = bucketSize;
        this.blockSize = blockSize;
        this.sharedStash = sharedStash;
    }

    public ChunkAccessResult accessChunk(int bid,
                                         int readPid,
                                         int writePid,
                                         int successorWritePid) {
        int seq = manager.registerAccess();
        ORAMPath path = encryptionManager.decryptPath(manager.readPath(readPid));

        Map<Integer, ORAMBlock> candidates = collectLatest(path);
        synchronized (sharedStash) {
            for (ORAMBlock block : sharedStash.values()) {
                mergeLatest(candidates, block);
            }
            sharedStash.clear();
        }

        ORAMBlock target = candidates.get(bid);
        if (target == null) {
            throw new IllegalStateException(
                    "Cannot find SSE chunk bid=" + bid + " on pid=" + readPid + " or in stash"
            );
        }
        SSEChunk oldChunk = SSEChunkCodec.decode(target.getData());
        SSEChunk rewrittenChunk = new SSEChunk(
                oldChunk.getKeywordId(),
                oldChunk.getChunkIndex(),
                oldChunk.getNextBid(),
                oldChunk.getNextBid() == SSEChunk.END_OF_LIST
                        ? SSEChunk.END_OF_LIST
                        : successorWritePid,
                oldChunk.getDocumentIds()
        );
        candidates.put(
                bid,
                new ORAMBlock(
                        bid,
                        SSEChunkCodec.encode(rewrittenChunk, blockSize),
                        writePid,
                        seq,
                        false
                )
        );

        ORAMPath rebuilt = rebuildPath(readPid, path, candidates);
        manager.submitTurn(
                seq,
                encryptionManager.encryptPath(rebuilt),
                new EncryptedMapUpdate(new byte[0])
        );
        return new ChunkAccessResult(oldChunk, writePid, successorWritePid);
    }

    public int randomPid() {
        return random.nextInt(leafCount);
    }

    private Map<Integer, ORAMBlock> collectLatest(ORAMPath path) {
        Map<Integer, ORAMBlock> latest = new HashMap<>();
        for (ORAMBucket bucket : path.getBuckets()) {
            for (ORAMBlock block : bucket.getBlocks()) {
                if (block != null && !block.isDummy()) {
                    mergeLatest(latest, block);
                }
            }
        }
        return latest;
    }

    private ORAMPath rebuildPath(int pathPid,
                                 ORAMPath original,
                                 Map<Integer, ORAMBlock> candidates) {
        ORAMBucket[] buckets = new ORAMBucket[treeHeight];
        for (int level = 0; level < treeHeight; level++) {
            int slots = level == 0 ? rootBucketSize : bucketSize;
            buckets[level] = ORAMBucket.weighted(
                    original.getBucket(level).getBucketId(),
                    slots * blockSize,
                    new ORAMBlock[0]
            );
        }

        List<ORAMBlock> ordered = new ArrayList<>(candidates.values());
        ordered.sort(
                Comparator.comparingInt((ORAMBlock block) ->
                                deepestCommonLevel(pathPid, block.getPid()))
                        .thenComparing(
                                Comparator.comparingInt(
                                        (ORAMBlock block) -> block.getData().length
                                ).reversed()
                        )
        );

        Map<Integer, ORAMBlock> nextStash = new HashMap<>();
        for (ORAMBlock block : ordered) {
            if (!placeOnPathBottomUp(buckets, pathPid, block)) {
                nextStash.put(block.getBid(), copyBlock(block));
            }
        }
        synchronized (sharedStash) {
            sharedStash.putAll(nextStash);
        }
        return new ORAMPath(pathPid, buckets);
    }

    private boolean placeOnPathBottomUp(ORAMBucket[] buckets,
                                        int pathPid,
                                        ORAMBlock block) {
        int deepest = deepestCommonLevel(pathPid, block.getPid());
        for (int level = deepest; level >= 0; level--) {
            ORAMBucket bucket = buckets[level];
            if (bucket.getUsedBytes() + block.getData().length <= bucket.getCapacityBytes()) {
                ORAMBlock[] existing = bucket.getBlocks();
                ORAMBlock[] updated = Arrays.copyOf(existing, existing.length + 1);
                updated[existing.length] = copyBlock(block);
                bucket.setBlocks(updated);
                return true;
            }
        }
        return false;
    }

    private int deepestCommonLevel(int a, int b) {
        int x = a ^ b;
        if (x == 0) {
            return treeHeight - 1;
        }
        return (treeHeight - 2) - (31 - Integer.numberOfLeadingZeros(x));
    }

    private static void mergeLatest(Map<Integer, ORAMBlock> blocks, ORAMBlock candidate) {
        ORAMBlock current = blocks.get(candidate.getBid());
        if (current == null || candidate.getSeq() > current.getSeq()) {
            blocks.put(candidate.getBid(), copyBlock(candidate));
        }
    }

    private static ORAMBlock copyBlock(ORAMBlock block) {
        return new ORAMBlock(
                block.getBid(),
                Arrays.copyOf(block.getData(), block.getData().length),
                block.getPid(),
                block.getSeq(),
                block.isDummy()
        );
    }

    public static final class ChunkAccessResult {
        private final SSEChunk chunk;
        private final int writePid;
        private final int successorWritePid;

        private ChunkAccessResult(SSEChunk chunk, int writePid, int successorWritePid) {
            this.chunk = chunk;
            this.writePid = writePid;
            this.successorWritePid = successorWritePid;
        }

        public SSEChunk getChunk() {
            return chunk;
        }

        public int getWritePid() {
            return writePid;
        }

        public int getSuccessorWritePid() {
            return successorWritePid;
        }
    }
}
