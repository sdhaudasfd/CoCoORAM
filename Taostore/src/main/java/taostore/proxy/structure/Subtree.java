package taostore.proxy.structure;

import oram.common.Bucket;
import oram.common.ORAMUtils;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class Subtree {
    private final ConcurrentHashMap<Integer, BucketEntry> buckets = new ConcurrentHashMap<>();

    public static class BucketEntry {
        private volatile Bucket bucket;
        private volatile int localTimestamp;
        private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

        public Bucket getBucket() {
            return bucket;
        }

        public void setBucket(Bucket bucket) {
            this.bucket = bucket;
        }

        public int getLocalTimestamp() {
            return localTimestamp;
        }

        public void setLocalTimestamp(int localTimestamp) {
            this.localTimestamp = localTimestamp;
        }

        public ReentrantReadWriteLock getLock() {
            return lock;
        }
    }

    public void insertOrUpdate(int bucketId, Bucket bucket, int localTimestamp) {
        BucketEntry entry = buckets.computeIfAbsent(bucketId, k -> new BucketEntry());
        entry.getLock().writeLock().lock();
        try {
            entry.setBucket(bucket);
            entry.setLocalTimestamp(localTimestamp);
        } finally {
            entry.getLock().writeLock().unlock();
        }
    }

    public Bucket get(int bucketId) {
        BucketEntry entry = buckets.get(bucketId);
        if (entry == null) {
            return null;
        }
        entry.getLock().readLock().lock();
        try {
            return entry.getBucket();
        } finally {
            entry.getLock().readLock().unlock();
        }
    }

    public BucketEntry getEntry(int bucketId) {
        return buckets.get(bucketId);
    }

    public boolean contains(int bucketId) {
        return buckets.containsKey(bucketId);
    }

    public int size() {
        return buckets.size();
    }

    public void syncFromServer(int bucketId, Bucket plaintextBucket) {
        buckets.computeIfAbsent(bucketId, k -> {
            BucketEntry entry = new BucketEntry();
            entry.setBucket(plaintextBucket);
            entry.setLocalTimestamp(0);
            return entry;
        });
    }

    public Map<Integer, BucketEntry> snapshot() {
        return new ConcurrentHashMap<>(buckets);
    }

    public ConcurrentHashMap<Integer, BucketEntry> getAllEntries() {
        return buckets;
    }

    public void pruneAfterWriteBack(int serverTimestamp, int k, PathReqMultiSet inFlight, int treeHeight) {
        int threshold = serverTimestamp * k;
        Set<Integer> protectedBuckets = new HashSet<>();
        for (int pathId : inFlight.snapshotPathIds()) {
            int[] pathLocations = ORAMUtils.computePathLocations(pathId, treeHeight);
            for (int location : pathLocations) {
                protectedBuckets.add(location);
            }
        }

        Iterator<Map.Entry<Integer, BucketEntry>> iterator = buckets.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Integer, BucketEntry> entry = iterator.next();
            int bucketId = entry.getKey();
            BucketEntry bucketEntry = entry.getValue();
            bucketEntry.getLock().readLock().lock();
            try {
                if (bucketEntry.getLocalTimestamp() <= threshold && !protectedBuckets.contains(bucketId)) {
                    iterator.remove();
                }
            } finally {
                bucketEntry.getLock().readLock().unlock();
            }
        }
    }
}
