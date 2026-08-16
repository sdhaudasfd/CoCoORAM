package taostore.proxy;

import comunication.CommunicationSystem;
import comunication.Message;
import oram.common.Block;
import oram.common.Bucket;
import oram.common.ORAMContext;
import oram.common.ORAMUtils;
import oram.common.Stash;
import oram.security.EncryptionManager;
import oram.server.structure.EncryptedBucket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import taostore.messages.ClientRequestMessage;
import taostore.messages.MessageTypes;
import taostore.messages.ReadPathRequestMessage;
import taostore.messages.ReadPathResponseMessage;
import taostore.messages.WriteBackAckMessage;
import taostore.messages.WriteBackRequestMessage;
import taostore.proxy.structure.LogicalRequest;
import taostore.proxy.structure.PathReqMultiSet;
import taostore.proxy.structure.PositionMap;
import taostore.proxy.structure.RequestMap;
import taostore.proxy.structure.ResponseMap;
import taostore.proxy.structure.Subtree;

import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

public class Processor {
    private final Logger logger = LoggerFactory.getLogger("taostore.proxy");

    private final int proxyId;
    private final int serverId;
    private final ORAMContext context;
    private final int writeBackThreshold;

    private final Stash stash;
    private final PositionMap positionMap;
    private final Subtree subtree;
    private final RequestMap requestMap;
    private final ResponseMap responseMap;
    private final PathReqMultiSet pathReqMultiSet;

    private final EncryptionManager encryptionManager;
    private final SecureRandom rng;

    private final AtomicInteger pathsCounter;
    private final ArrayDeque<Integer> writeQueue;
    private final ReentrantLock writeQueueLock;
    private final ReentrantLock stashLock;
    private final AtomicInteger serverTimestampCounter;

    private final CommunicationSystem serverComm;

    private final ConcurrentHashMap<Long, CompletableFuture<ReadPathResponseMessage>> pendingReads;
    private final ConcurrentHashMap<Integer, CompletableFuture<WriteBackAckMessage>> pendingWriteBackAcks;
    private final AtomicLong correlationCounter;

    private final ExecutorService threadPool;

    private final AtomicInteger activeRequests;
    private final AtomicInteger activeWriteBacks;
    private final AtomicLong readRoundTripNsSum;
    private final AtomicLong readRoundTripCount;

    private static final long READ_PATH_TIMEOUT_SECONDS = 30;
    private static final long WRITE_BACK_ACK_TIMEOUT_SECONDS = 30;

    private volatile Sequencer sequencer;

    public Processor(int proxyId,
                     int serverId,
                     ORAMContext context,
                     int writeBackThreshold,
                     CommunicationSystem serverComm) {
        this.proxyId = proxyId;
        this.serverId = serverId;
        this.context = context;
        this.writeBackThreshold = writeBackThreshold;

        this.stash = new Stash(context.getBlockSize());
        this.positionMap = new PositionMap(context.getTreeSize(), context.getTreeHeight());
        this.subtree = new Subtree();
        this.requestMap = new RequestMap();
        this.responseMap = new ResponseMap();
        this.pathReqMultiSet = new PathReqMultiSet();

        this.encryptionManager = new EncryptionManager();
        this.rng = new SecureRandom();

        this.pathsCounter = new AtomicInteger(0);
        this.writeQueue = new ArrayDeque<>();
        this.writeQueueLock = new ReentrantLock();
        this.stashLock = new ReentrantLock();
        this.serverTimestampCounter = new AtomicInteger(1);

        this.serverComm = serverComm;

        this.pendingReads = new ConcurrentHashMap<>();
        this.pendingWriteBackAcks = new ConcurrentHashMap<>();
        this.correlationCounter = new AtomicLong(1);

        this.threadPool = Executors.newCachedThreadPool();

        this.activeRequests = new AtomicInteger(0);
        this.activeWriteBacks = new AtomicInteger(0);
        this.readRoundTripNsSum = new AtomicLong(0);
        this.readRoundTripCount = new AtomicLong(0);
    }

    public void setSequencer(Sequencer sequencer) {
        this.sequencer = sequencer;
    }

    public void submitRequest(LogicalRequest lr) {
        threadPool.submit(() -> {
            activeRequests.incrementAndGet();
            try {
                PathResult pathResult = readPath(lr);
                answerRequest(lr, pathResult.pathId, pathResult.path, pathResult.fakeRead);
                int currentPaths = flush(pathResult.pathId);
                if (currentPaths > 0 && currentPaths % writeBackThreshold == 0) {
                    int ts = serverTimestampCounter.getAndIncrement();
                    threadPool.submit(() -> writeBack(ts, writeBackThreshold));
                }
            } catch (Exception e) {
                logger.error("Failed to process logical request {}", lr.getRequestId(), e);
                if (sequencer != null) {
                    sequencer.notifyReply(lr.getRequestId(), new byte[0]);
                }
            } finally {
                activeRequests.decrementAndGet();
            }
        });
    }

    private PathResult readPath(LogicalRequest lr) throws Exception {
        responseMap.create(lr.getRequestId());

        boolean isFirstInQueue = requestMap.enqueue(lr);
        int pathId;
        boolean fakeRead;
        if (isFirstInQueue) {
            fakeRead = false;
            pathId = positionMap.getAndRefresh(lr.getBlockAddress());
        } else {
            fakeRead = true;
            pathId = rng.nextInt(1 << context.getTreeHeight());
        }
        lr.setFakeRead(fakeRead);

        long correlationId = correlationCounter.getAndIncrement();
        CompletableFuture<ReadPathResponseMessage> future = new CompletableFuture<>();
        pendingReads.put(correlationId, future);

        pathReqMultiSet.add(pathId);
        ReadPathRequestMessage req = new ReadPathRequestMessage(correlationId, pathId);
        long start = System.nanoTime();
        serverComm.sendMessage(serverId, new Message(proxyId, MessageTypes.READ_PATH_REQUEST, req.toBytes()));

        ReadPathResponseMessage resp;
        try {
            resp = future.get(READ_PATH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            pendingReads.remove(correlationId);
            throw new RuntimeException("Timeout waiting READ_PATH_RESPONSE for correlationId=" + correlationId, e);
        } finally {
            pathReqMultiSet.remove(pathId);
        }
        long end = System.nanoTime();
        readRoundTripNsSum.addAndGet(end - start);
        readRoundTripCount.incrementAndGet();

        Bucket[] path = new Bucket[resp.getBuckets().length];
        for (int i = 0; i < resp.getBuckets().length; i++) {
            path[i] = encryptionManager.decryptBucket(context, resp.getBuckets()[i]);
        }
        return new PathResult(pathId, path, fakeRead);
    }

    private void answerRequest(LogicalRequest lr, int pathId, Bucket[] path, boolean isFakeRead) {
        int[] pathLocations = ORAMUtils.computePathLocations(pathId, context.getTreeHeight());

        for (int i = 0; i < pathLocations.length && i < path.length; i++) {
            int bucketId = pathLocations[i];
            if (!subtree.contains(bucketId)) {
                subtree.syncFromServer(bucketId, path[i]);
            }
        }

        responseMap.markReady(lr.getRequestId());
        if (responseMap.canReply(lr.getRequestId())) {
            byte[] val = responseMap.getValue(lr.getRequestId());
            responseMap.remove(lr.getRequestId());
            if (sequencer != null) {
                sequencer.notifyReply(lr.getRequestId(), val);
            }
        }

        if (isFakeRead) {
            return;
        }

        Block target = extractBlock(lr.getBlockAddress());
        List<LogicalRequest> queue = requestMap.drainQueue(lr.getBlockAddress());
        byte[] currentValue = target == null ? null : target.getContent();

        for (LogicalRequest pending : queue) {
            byte[] returnValue = currentValue;
            if (pending.getOpType() == ClientRequestMessage.WRITE) {
                currentValue = pending.getNewValue();
                if (target == null) {
                    target = new Block(context.getBlockSize(), pending.getBlockAddress(), pathsCounter.get(), currentValue);
                } else {
                    target.setContent(currentValue);
                    target.setVersion(pathsCounter.get());
                    target.setAccess(pathsCounter.get());
                }
            }

            responseMap.setValue(pending.getRequestId(), returnValue == null ? new byte[0] : returnValue);
            responseMap.markReady(pending.getRequestId());
            if (responseMap.canReply(pending.getRequestId())) {
                byte[] value = responseMap.getValue(pending.getRequestId());
                responseMap.remove(pending.getRequestId());
                if (sequencer != null) {
                    sequencer.notifyReply(pending.getRequestId(), value);
                }
            }
        }

        if (target != null) {
            stashLock.lock();
            try {
                stash.putBlock(target);
            } finally {
                stashLock.unlock();
            }
        }
    }

    private Block extractBlock(int blockAddress) {
        stashLock.lock();
        try {
            Block stashBlock = stash.getAndRemoveBlock(blockAddress);
            if (stashBlock != null) {
                return stashBlock;
            }
        } finally {
            stashLock.unlock();
        }

        for (Map.Entry<Integer, Subtree.BucketEntry> entry : subtree.getAllEntries().entrySet()) {
            Subtree.BucketEntry bucketEntry = entry.getValue();
            bucketEntry.getLock().writeLock().lock();
            try {
                Bucket bucket = bucketEntry.getBucket();
                if (bucket == null) {
                    continue;
                }
                Block[] blocks = bucket.getBlocks();
                for (int i = 0; i < blocks.length; i++) {
                    Block block = blocks[i];
                    if (block != null && block.getAddress() == blockAddress) {
                        blocks[i] = null;
                        bucketEntry.setLocalTimestamp(pathsCounter.get());
                        return block;
                    }
                }
            } finally {
                bucketEntry.getLock().writeLock().unlock();
            }
        }

        return null;
    }

    private int flush(int pathId) {
        int[] pathLocations = ORAMUtils.computePathLocations(pathId, context.getTreeHeight());

        List<Integer> addresses;
        stashLock.lock();
        try {
            addresses = new ArrayList<>(stash.getBlocks().keySet());
        } finally {
            stashLock.unlock();
        }
        for (int blockAddress : addresses) {
            int assignedPath = positionMap.get(blockAddress);
            int[] assignedPathLocations = ORAMUtils.computePathLocations(assignedPath, context.getTreeHeight());
            List<Integer> intersection = ORAMUtils.computePathIntersection(
                    context.getTreeLevels(), pathLocations, assignedPathLocations
            );
            if (intersection.isEmpty()) {
                continue;
            }

            int targetBucketId = intersection.get(intersection.size() - 1);
            Bucket bucket = subtree.get(targetBucketId);
            if (bucket == null) {
                continue;
            }
            int emptySlot = findEmptySlot(bucket);
            if (emptySlot >= 0) {
                Block block;
                stashLock.lock();
                try {
                    block = stash.getAndRemoveBlock(blockAddress);
                } finally {
                    stashLock.unlock();
                }
                if (block != null) {
                    bucket.putBlock(emptySlot, block);
                    subtree.insertOrUpdate(targetBucketId, bucket, pathsCounter.get() + 1);
                }
            }
        }

        int newCount = pathsCounter.incrementAndGet();
        writeQueueLock.lock();
        try {
            writeQueue.addLast(pathId);
        } finally {
            writeQueueLock.unlock();
        }
        return newCount;
    }

    private int findEmptySlot(Bucket bucket) {
        Block[] blocks = bucket.getBlocks();
        for (int i = 0; i < blocks.length; i++) {
            if (blocks[i] == null) {
                return i;
            }
        }
        return -1;
    }

    private void writeBack(int c, int maxPaths) {
        activeWriteBacks.incrementAndGet();
        int[] paths;
        writeQueueLock.lock();
        try {
            int pathCount = Math.min(maxPaths, writeQueue.size());
            if (pathCount <= 0 || (maxPaths == writeBackThreshold && pathCount < writeBackThreshold)) {
                activeWriteBacks.decrementAndGet();
                return;
            }
            paths = new int[pathCount];
            for (int i = 0; i < pathCount; i++) {
                paths[i] = writeQueue.removeFirst();
            }
        } finally {
            writeQueueLock.unlock();
        }

        Map<Integer, EncryptedBucket[]> snapshot = new LinkedHashMap<>();
        for (int pathId : paths) {
            int[] pathLocations = ORAMUtils.computePathLocations(pathId, context.getTreeHeight());
            EncryptedBucket[] buckets = new EncryptedBucket[pathLocations.length];
            for (int i = 0; i < pathLocations.length; i++) {
                int bucketId = pathLocations[i];
                Bucket bucket = subtree.get(bucketId);
                if (bucket == null) {
                    bucket = new Bucket(context.getBucketSize(), context.getBlockSize(), bucketId);
                }
                buckets[i] = encryptionManager.encryptBucket(context, bucket);
            }
            snapshot.put(pathId, buckets);
        }

        CompletableFuture<WriteBackAckMessage> future = new CompletableFuture<>();
        pendingWriteBackAcks.put(c, future);

        WriteBackRequestMessage request = new WriteBackRequestMessage(c, snapshot);
        serverComm.sendMessage(serverId, new Message(proxyId, MessageTypes.WRITE_BACK_REQUEST, request.toBytes()));

        try {
            future.get(WRITE_BACK_ACK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            subtree.pruneAfterWriteBack(c, writeBackThreshold, pathReqMultiSet, context.getTreeHeight());
        } catch (TimeoutException e) {
            logger.error("Timeout waiting write-back ack c={}", c, e);
        } catch (Exception e) {
            logger.error("Failed write-back c={}", c, e);
        } finally {
            pendingWriteBackAcks.remove(c);
            activeWriteBacks.decrementAndGet();
        }
    }

    public void drain() throws InterruptedException {
        while (activeRequests.get() > 0) {
            Thread.sleep(1);
        }

        while (true) {
            while (activeWriteBacks.get() > 0) {
                Thread.sleep(1);
            }

            int remaining;
            writeQueueLock.lock();
            try {
                remaining = writeQueue.size();
            } finally {
                writeQueueLock.unlock();
            }

            if (remaining <= 0) {
                return;
            }

            int ts = serverTimestampCounter.getAndIncrement();
            writeBack(ts, remaining);
        }
    }

    public void onReadPathResponse(ReadPathResponseMessage response) {
        CompletableFuture<ReadPathResponseMessage> future = pendingReads.remove(response.getCorrelationId());
        if (future != null) {
            future.complete(response);
        }
    }

    public void onWriteBackAck(WriteBackAckMessage ack) {
        CompletableFuture<WriteBackAckMessage> future = pendingWriteBackAcks.get(ack.getServerTimestamp());
        if (future != null) {
            future.complete(ack);
        }
    }

    public int getActiveRequests() {
        return activeRequests.get();
    }

    public int getSubtreeSize() {
        return subtree.size();
    }

    public int getStashSize() {
        stashLock.lock();
        try {
            return stash.size();
        } finally {
            stashLock.unlock();
        }
    }

    public int getWriteQueueSize() {
        writeQueueLock.lock();
        try {
            return writeQueue.size();
        } finally {
            writeQueueLock.unlock();
        }
    }

    public double getAndResetServerRoundTripMs() {
        long count = readRoundTripCount.getAndSet(0);
        if (count == 0) {
            return -1;
        }
        long sum = readRoundTripNsSum.getAndSet(0);
        return (sum / (double) count) / 1_000_000.0;
    }

    private static class PathResult {
        private final int pathId;
        private final Bucket[] path;
        private final boolean fakeRead;

        private PathResult(int pathId, Bucket[] path, boolean fakeRead) {
            this.pathId = pathId;
            this.path = path;
            this.fakeRead = fakeRead;
        }
    }
}
