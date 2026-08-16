package opca.server.structure;

import oram.server.structure.EncryptedBucket;

import java.util.concurrent.locks.ReentrantReadWriteLock;

public class TimestampedBucket {
    private volatile EncryptedBucket bucket;
    private volatile int timestamp;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public TimestampedBucket(EncryptedBucket bucket, int timestamp) {
        this.bucket = bucket;
        this.timestamp = timestamp;
    }

    public EncryptedBucket getBucket() {
        return bucket;
    }

    public int getTimestamp() {
        return timestamp;
    }

    public ReentrantReadWriteLock getLock() {
        return lock;
    }

    public void overwrite(EncryptedBucket bucket, int timestamp) {
        this.bucket = bucket;
        this.timestamp = timestamp;
    }
}
