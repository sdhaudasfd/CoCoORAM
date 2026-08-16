package opca.proxy;

import comunication.CommunicationSystem;
import comunication.Message;
import opca.messages.ClientRequestMessage;
import opca.messages.ClientResponseMessage;
import opca.messages.MessageTypes;
import opca.messages.ReadPathRequestMessage;
import opca.messages.ReadPathResponseMessage;
import opca.proxy.structure.LocalSubtree;
import opca.proxy.structure.OpcaBuffer;
import opca.proxy.structure.OpcaBufferEntry;
import opca.proxy.structure.OpcaMap;
import opca.proxy.structure.OpcaMapEntry;
import opca.proxy.structure.PositionMap;
import opca.proxy.structure.PositionMapEntry;
import oram.common.Block;
import oram.common.Bucket;
import oram.common.ORAMContext;
import oram.common.ORAMUtils;
import oram.security.EncryptionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

public class OpcaScheduler {
    private static final Logger logger = LoggerFactory.getLogger("opca.proxy");
    private static final long READ_PATH_TIMEOUT_SECONDS = 30;

    private final PositionMap positionMap;
    private final OpcaMap opcaMap;
    private final OpcaBuffer opcaBuffer;
    private final LocalSubtree localSubtree;
    private final ORAMContext context;
    private final EncryptionManager encryptionManager;
    private final CommunicationSystem serverComm;
    private final CommunicationSystem clientComm;
    private final int proxyId;
    private final int serverId;
    private final ConcurrentHashMap<Long, CompletableFuture<ReadPathResponseMessage>> pendingReads;
    private final AtomicLong correlationCounter;
    private final ExecutorService threadPool;
    private final Object mapLock;
    private final Object idleLock;
    private final SecureRandom rng;
    private int activeRequests;

    private final AtomicLong intervalCompletedCount;
    private final AtomicLong intervalLatencyNsSum;
    private final AtomicLong intervalServerRoundTripNsSum;
    private final AtomicLong intervalServerRoundTripCount;
    private final AtomicLong intervalReadCount;
    private final AtomicLong intervalSubtreeHitCount;
    private final AtomicLong intervalBufferHitCount;
    private final AtomicLong intervalMapLockWaitNs;
    private final AtomicLong intervalMapLockWaitCount;

    public OpcaScheduler(PositionMap positionMap,
                         OpcaMap opcaMap,
                         OpcaBuffer opcaBuffer,
                         LocalSubtree localSubtree,
                         ORAMContext context,
                         CommunicationSystem serverComm,
                         CommunicationSystem clientComm,
                         int proxyId,
                         int serverId,
                         ConcurrentHashMap<Long, CompletableFuture<ReadPathResponseMessage>> pendingReads,
                         AtomicLong correlationCounter) {
        this.positionMap = positionMap;
        this.opcaMap = opcaMap;
        this.opcaBuffer = opcaBuffer;
        this.localSubtree = localSubtree;
        this.context = context;
        this.encryptionManager = new EncryptionManager();
        this.serverComm = serverComm;
        this.clientComm = clientComm;
        this.proxyId = proxyId;
        this.serverId = serverId;
        this.pendingReads = pendingReads;
        this.correlationCounter = correlationCounter;
        this.threadPool = Executors.newCachedThreadPool();
        this.mapLock = new Object();
        this.idleLock = new Object();
        this.rng = new SecureRandom();
        this.activeRequests = 0;

        this.intervalCompletedCount = new AtomicLong(0);
        this.intervalLatencyNsSum = new AtomicLong(0);
        this.intervalServerRoundTripNsSum = new AtomicLong(0);
        this.intervalServerRoundTripCount = new AtomicLong(0);
        this.intervalReadCount = new AtomicLong(0);
        this.intervalSubtreeHitCount = new AtomicLong(0);
        this.intervalBufferHitCount = new AtomicLong(0);
        this.intervalMapLockWaitNs = new AtomicLong(0);
        this.intervalMapLockWaitCount = new AtomicLong(0);
    }

    public void dispatch(int clientId, ClientRequestMessage req) {
        synchronized (idleLock) {
            activeRequests++;
        }
        threadPool.submit(() -> {
            long t1 = System.nanoTime();
            try {
                if (req.getOpType() == ClientRequestMessage.WRITE) {
                    handleWrite(clientId, req);
                } else {
                    handleRead(clientId, req);
                }
            } catch (Exception e) {
                logger.error("Failed to process request id={} address={} op={}",
                        req.getRequestId(), req.getBlockAddress(), req.getOpType(), e);
                sendClientResponse(clientId, req.getRequestId(), new byte[0]);
            } finally {
                long latency = System.nanoTime() - t1;
                intervalLatencyNsSum.addAndGet(latency);
                intervalCompletedCount.incrementAndGet();
                synchronized (idleLock) {
                    activeRequests--;
                    if (activeRequests == 0) {
                        idleLock.notifyAll();
                    }
                }
            }
        });
    }

    public void awaitIdle() throws InterruptedException {
        synchronized (idleLock) {
            while (activeRequests > 0) {
                idleLock.wait(10);
            }
        }
    }

    private void handleRead(int clientId, ClientRequestMessage req) throws Exception {
        intervalReadCount.incrementAndGet();

        PositionMapEntry pmEntry = positionMap.get(req.getBlockAddress());
        if (pmEntry == null) {
            sendClientResponse(clientId, req.getRequestId(), new byte[0]);
            return;
        }

        int count = pmEntry.getCount().get();
        int pathId = pmEntry.getPathId();

        byte[] resultData;

        if (count > 0) {
            OpcaMapEntry latest = opcaMap.getLatestVersion(req.getBlockAddress());
            if (latest == null) {
                Thread.sleep(1);
                latest = opcaMap.getLatestVersion(req.getBlockAddress());
            }

            if (latest != null) {
                OpcaBufferEntry entry = opcaBuffer.read(latest.getBufIndex());
                resultData = entry == null ? new byte[context.getBlockSize()] : entry.getData();
                intervalBufferHitCount.incrementAndGet();
            } else {
                resultData = fetchFromSubtreeOrServer(req.getBlockAddress(), pathId);
            }
            sendFakeRead();
        } else {
            byte[] subtreeData = localSubtree.findBlock(req.getBlockAddress());
            if (subtreeData != null) {
                resultData = subtreeData;
                intervalSubtreeHitCount.incrementAndGet();
                sendFakeRead();
            } else {
                resultData = fetchRealPath(req.getBlockAddress(), pathId);
            }
        }

        sendClientResponse(clientId, req.getRequestId(), resultData);
    }

    private void handleWrite(int clientId, ClientRequestMessage req) throws Exception {
        long timestamp = System.nanoTime();
        int bufIndex = opcaBuffer.write(req.getBlockAddress(), req.getData(), timestamp);

        long waitStart = System.nanoTime();
        synchronized (mapLock) {
            long waited = System.nanoTime() - waitStart;
            intervalMapLockWaitNs.addAndGet(waited);
            intervalMapLockWaitCount.incrementAndGet();

            positionMap.incrementCount(req.getBlockAddress());
            opcaMap.addVersion(req.getBlockAddress(), bufIndex, timestamp);
        }

        sendFakeRead();
        sendClientResponse(clientId, req.getRequestId(), null);
    }

    private byte[] fetchFromSubtreeOrServer(int blockAddress, int pathId) throws Exception {
        byte[] fromSubtree = localSubtree.findBlock(blockAddress);
        if (fromSubtree != null) {
            intervalSubtreeHitCount.incrementAndGet();
            return fromSubtree;
        }
        return fetchRealPath(blockAddress, pathId);
    }

    private byte[] fetchRealPath(int blockAddress, int pathId) throws Exception {
        long corrId = correlationCounter.getAndIncrement();
        CompletableFuture<ReadPathResponseMessage> future = new CompletableFuture<>();
        pendingReads.put(corrId, future);

        ReadPathRequestMessage msg = new ReadPathRequestMessage(corrId, pathId, false);
        long t1 = System.nanoTime();
        serverComm.sendMessage(serverId, new Message(proxyId, MessageTypes.READ_PATH_REQUEST, msg.toBytes()));

        ReadPathResponseMessage resp;
        try {
            resp = future.get(READ_PATH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new RuntimeException("Timeout waiting READ_PATH_RESPONSE for corrId=" + corrId, e);
        } finally {
            pendingReads.remove(corrId);
        }
        intervalServerRoundTripNsSum.addAndGet(System.nanoTime() - t1);
        intervalServerRoundTripCount.incrementAndGet();

        int[] locs = ORAMUtils.computePathLocations(pathId, context.getTreeHeight());
        Bucket[] buckets = new Bucket[resp.getBuckets().length];
        for (int i = 0; i < resp.getBuckets().length && i < locs.length; i++) {
            buckets[i] = encryptionManager.decryptBucket(context, resp.getBuckets()[i]);
        }
        localSubtree.insertPath(locs, buckets);

        for (Bucket bucket : buckets) {
            if (bucket == null) {
                continue;
            }
            for (Block block : bucket.getBlocks()) {
                if (block != null && block.getAddress() == blockAddress) {
                    return block.getContent();
                }
            }
        }

        return new byte[context.getBlockSize()];
    }

    private void sendFakeRead() throws Exception {
        int fakePath = rng.nextInt(1 << context.getTreeHeight());
        long corrId = correlationCounter.getAndIncrement();
        CompletableFuture<ReadPathResponseMessage> future = new CompletableFuture<>();
        pendingReads.put(corrId, future);

        ReadPathRequestMessage msg = new ReadPathRequestMessage(corrId, fakePath, true);
        long t1 = System.nanoTime();
        serverComm.sendMessage(serverId, new Message(proxyId, MessageTypes.READ_PATH_REQUEST, msg.toBytes()));

        ReadPathResponseMessage resp;
        try {
            resp = future.get(READ_PATH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new RuntimeException("Timeout waiting fake READ_PATH_RESPONSE for corrId=" + corrId, e);
        } finally {
            pendingReads.remove(corrId);
        }
        intervalServerRoundTripNsSum.addAndGet(System.nanoTime() - t1);
        intervalServerRoundTripCount.incrementAndGet();

        int[] locs = ORAMUtils.computePathLocations(fakePath, context.getTreeHeight());
        Bucket[] buckets = new Bucket[resp.getBuckets().length];
        for (int i = 0; i < resp.getBuckets().length && i < locs.length; i++) {
            buckets[i] = encryptionManager.decryptBucket(context, resp.getBuckets()[i]);
        }
        localSubtree.insertPath(locs, buckets);
    }

    private void sendClientResponse(int clientId, long requestId, byte[] value) {
        if (!clientComm.sessionExists(clientId)) {
            logger.debug("Skip response for disconnected client {} requestId={}", clientId, requestId);
            return;
        }
        ClientResponseMessage response = new ClientResponseMessage(requestId, value);
        clientComm.sendMessage(clientId, new Message(proxyId, MessageTypes.CLIENT_RESPONSE, response.toBytes()));
    }

    public long getAndResetCompletedCount() {
        return intervalCompletedCount.getAndSet(0);
    }

    public double getAndResetAvgLatencyMs() {
        long count = intervalCompletedCount.get();
        if (count == 0) {
            intervalLatencyNsSum.getAndSet(0);
            return -1;
        }
        long ns = intervalLatencyNsSum.getAndSet(0);
        return (ns / (double) count) / 1_000_000.0;
    }

    public double getAndResetServerRoundTripMs() {
        long count = intervalServerRoundTripCount.getAndSet(0);
        if (count == 0) {
            intervalServerRoundTripNsSum.getAndSet(0);
            return -1;
        }
        long sum = intervalServerRoundTripNsSum.getAndSet(0);
        return (sum / (double) count) / 1_000_000.0;
    }

    public double getAndResetSubtreeHitRate() {
        long totalReads = intervalReadCount.getAndSet(0);
        long hits = intervalSubtreeHitCount.getAndSet(0);
        if (totalReads == 0) {
            return 0;
        }
        return hits / (double) totalReads;
    }

    public double getAndResetBufferHitRate() {
        long totalReads = intervalReadCount.get();
        long hits = intervalBufferHitCount.getAndSet(0);
        if (totalReads == 0) {
            return 0;
        }
        return hits / (double) totalReads;
    }

    public double getAndResetContentionLockWaitUs() {
        long count = intervalMapLockWaitCount.getAndSet(0);
        if (count == 0) {
            intervalMapLockWaitNs.getAndSet(0);
            return 0;
        }
        long ns = intervalMapLockWaitNs.getAndSet(0);
        return (ns / (double) count) / 1_000.0;
    }
}
