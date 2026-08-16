package opca.proxy.structure;

import oram.common.Block;
import oram.common.Bucket;
import oram.common.ORAMContext;
import oram.common.ORAMUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class LocalSubtree {
    private final ConcurrentHashMap<Integer, Bucket> cache;
    private final ORAMContext context;
    private final AtomicInteger accessedPathNumber;

    public LocalSubtree(ORAMContext context) {
        this.cache = new ConcurrentHashMap<>();
        this.context = context;
        this.accessedPathNumber = new AtomicInteger(0);
    }

    public byte[] findBlock(int address) {
        for (Bucket bucket : cache.values()) {
            for (Block block : bucket.getBlocks()) {
                if (block != null && block.getAddress() == address) {
                    byte[] content = block.getContent();
                    return content == null ? null : Arrays.copyOf(content, content.length);
                }
            }
        }
        return null;
    }

    public boolean containsPath(int pathId) {
        int[] locs = ORAMUtils.computePathLocations(pathId, context.getTreeHeight());
        for (int loc : locs) {
            if (!cache.containsKey(loc)) {
                return false;
            }
        }
        return true;
    }

    public void insertPath(int[] bucketIds, Bucket[] buckets) {
        for (int i = 0; i < bucketIds.length && i < buckets.length; i++) {
            final int idx = i;
            cache.compute(bucketIds[i], (ignored, oldBucket) -> oldBucket == null ? buckets[idx] : oldBucket);
        }
        accessedPathNumber.incrementAndGet();
    }

    public void countPathAccess() {
        accessedPathNumber.incrementAndGet();
    }

    public void syncDirtyBlock(int address, byte[] data, int newPathId) {
        int[] locs = ORAMUtils.computePathLocations(newPathId, context.getTreeHeight());
        for (int i = locs.length - 1; i >= 0; i--) {
            int bucketId = locs[i];
            Bucket bucket = cache.computeIfAbsent(bucketId,
                    id -> new Bucket(context.getBucketSize(), context.getBlockSize(), id));
            for (int slot = 0; slot < context.getBucketSize(); slot++) {
                if (bucket.getBlock(slot) == null) {
                    bucket.putBlock(slot, new Block(context.getBlockSize(), address, 0, data));
                    return;
                }
            }
        }
    }

    public Map<Integer, Bucket> getAllBuckets() {
        return Collections.unmodifiableMap(cache);
    }

    public void clear() {
        cache.clear();
        accessedPathNumber.set(0);
    }

    public int getAccessedPathNumber() {
        return accessedPathNumber.get();
    }

    public int size() {
        return cache.size();
    }
}
