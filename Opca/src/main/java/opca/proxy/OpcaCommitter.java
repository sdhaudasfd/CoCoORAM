package opca.proxy;

import comunication.CommunicationSystem;
import comunication.Message;
import opca.messages.MessageTypes;
import opca.messages.ReadPathRequestMessage;
import opca.messages.ReadPathResponseMessage;
import opca.messages.WriteBackAckMessage;
import opca.messages.WriteBackRequestMessage;
import opca.proxy.structure.LocalSubtree;
import opca.proxy.structure.OpcaBuffer;
import opca.proxy.structure.OpcaBufferEntry;
import opca.proxy.structure.OpcaMap;
import opca.proxy.structure.OpcaMapEntry;
import opca.proxy.structure.PositionMap;
import opca.proxy.structure.PositionMapEntry;
import oram.common.Bucket;
import oram.common.ORAMContext;
import oram.common.ORAMUtils;
import oram.security.EncryptionManager;
import oram.server.structure.EncryptedBucket;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

public class OpcaCommitter {
    private static final long WRITE_BACK_ACK_TIMEOUT_SECONDS = 30;
    private static final long READ_PATH_TIMEOUT_SECONDS = 30;

    private final PositionMap positionMap;
    private final OpcaMap opcaMap;
    private final OpcaBuffer opcaBuffer;
    private final LocalSubtree localSubtree;
    private final ORAMContext context;
    private final EncryptionManager encryptionManager;
    private final CommunicationSystem serverComm;
    private final int serverId;
    private final int proxyId;
    private final ConcurrentHashMap<Long, CompletableFuture<ReadPathResponseMessage>> pendingReads;
    private final AtomicLong correlationCounter;
    private final ConcurrentHashMap<Integer, CompletableFuture<WriteBackAckMessage>> pendingWriteBackAcks;

    private final AtomicLong intervalWriteBackLatencyNs;

    public OpcaCommitter(PositionMap positionMap,
                         OpcaMap opcaMap,
                         OpcaBuffer opcaBuffer,
                         LocalSubtree localSubtree,
                         ORAMContext context,
                         CommunicationSystem serverComm,
                         int serverId,
                         int proxyId,
                         ConcurrentHashMap<Long, CompletableFuture<ReadPathResponseMessage>> pendingReads,
                         AtomicLong correlationCounter,
                         ConcurrentHashMap<Integer, CompletableFuture<WriteBackAckMessage>> pendingWriteBackAcks) {
        this.positionMap = positionMap;
        this.opcaMap = opcaMap;
        this.opcaBuffer = opcaBuffer;
        this.localSubtree = localSubtree;
        this.context = context;
        this.encryptionManager = new EncryptionManager();
        this.serverComm = serverComm;
        this.serverId = serverId;
        this.proxyId = proxyId;
        this.pendingReads = pendingReads;
        this.correlationCounter = correlationCounter;
        this.pendingWriteBackAcks = pendingWriteBackAcks;
        this.intervalWriteBackLatencyNs = new AtomicLong(0);
    }

    public void commitAndUpdate(int writeBackRound) throws Exception {
        long start = System.nanoTime();

        Map<Integer, byte[]> latestData = new HashMap<>();
        Set<Integer> dirtyAddresses = opcaMap.dirtyAddresses();

        for (int address : dirtyAddresses) {
            OpcaMapEntry latest = opcaMap.getLatestVersion(address);
            if (latest == null) {
                continue;
            }
            OpcaBufferEntry entry = opcaBuffer.read(latest.getBufIndex());
            if (entry != null && entry.getData() != null) {
                latestData.put(address, entry.getData());
            }
        }

        Set<Integer> loadedPaths = new HashSet<>();
        for (Map.Entry<Integer, byte[]> entry : latestData.entrySet()) {
            int address = entry.getKey();
            byte[] data = entry.getValue();
            positionMap.reassignPath(address);
            PositionMapEntry positionMapEntry = positionMap.get(address);
            int newPathId = positionMapEntry.getPathId();
            if (loadedPaths.add(newPathId)) {
                fetchPathIntoSubtree(newPathId);
            }
            localSubtree.syncDirtyBlock(address, data, newPathId);
        }

        opcaMap.clear();
        opcaBuffer.clear();

        writeSubtreeToServer(writeBackRound);

        localSubtree.clear();
        intervalWriteBackLatencyNs.addAndGet(System.nanoTime() - start);
    }

    private void fetchPathIntoSubtree(int pathId) throws Exception {
        long corrId = correlationCounter.getAndIncrement();
        CompletableFuture<ReadPathResponseMessage> future = new CompletableFuture<>();
        pendingReads.put(corrId, future);

        ReadPathRequestMessage request = new ReadPathRequestMessage(corrId, pathId, false);
        serverComm.sendMessage(serverId, new Message(proxyId, MessageTypes.READ_PATH_REQUEST, request.toBytes()));

        ReadPathResponseMessage response;
        try {
            response = future.get(READ_PATH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new RuntimeException("Timeout waiting READ_PATH_RESPONSE during commit pathId=" + pathId, e);
        } finally {
            pendingReads.remove(corrId);
        }

        int[] locs = ORAMUtils.computePathLocations(pathId, context.getTreeHeight());
        Bucket[] buckets = new Bucket[response.getBuckets().length];
        for (int i = 0; i < response.getBuckets().length && i < locs.length; i++) {
            buckets[i] = encryptionManager.decryptBucket(context, response.getBuckets()[i]);
        }
        localSubtree.insertPath(locs, buckets);
    }

    private void writeSubtreeToServer(int writeBackRound) throws Exception {
        Map<Integer, Bucket> allBuckets = localSubtree.getAllBuckets();
        Map<Integer, EncryptedBucket> encryptedBuckets = new HashMap<>();
        for (Map.Entry<Integer, Bucket> entry : allBuckets.entrySet()) {
            encryptedBuckets.put(entry.getKey(), encryptionManager.encryptBucket(context, entry.getValue()));
        }

        WriteBackRequestMessage writeBackRequestMessage = new WriteBackRequestMessage(writeBackRound, encryptedBuckets);
        CompletableFuture<WriteBackAckMessage> ackFuture = new CompletableFuture<>();
        pendingWriteBackAcks.put(writeBackRound, ackFuture);

        serverComm.sendMessage(serverId, new Message(proxyId, MessageTypes.WRITE_BACK_REQUEST, writeBackRequestMessage.toBytes()));

        try {
            ackFuture.get(WRITE_BACK_ACK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new RuntimeException("Timeout waiting WRITE_BACK_ACK for round=" + writeBackRound, e);
        } finally {
            pendingWriteBackAcks.remove(writeBackRound);
        }
    }

    public double getAndResetWriteBackLatencyMs() {
        long ns = intervalWriteBackLatencyNs.getAndSet(0);
        return ns / 1_000_000.0;
    }
}
