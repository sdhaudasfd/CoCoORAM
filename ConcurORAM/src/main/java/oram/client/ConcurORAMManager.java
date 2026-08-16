package oram.client;

import comunication.Message;
import oram.messages.ConcurMessages;
import oram.messages.InitGbMpRequest;
import oram.messages.InitGbMpResponse;
import oram.security.ProtocolEncryptionManager;
import oram.structure.EncryptedORAMBucket;
import oram.structure.EncryptedORAMPath;
import oram.structure.GlobalMapEntry;
import oram.structure.ORAMBlock;
import oram.structure.ORAMBucket;
import oram.structure.ORAMPath;
import oram.structure.UpdateMapEntry;
import oram.utils.Operation;
import oram.utils.ORAMUtils;
import oram.utils.RawCustomExternalizable;
import oram.utils.ServerOperationType;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class ConcurORAMManager {
    private static final int MESSAGE_TYPE = 1;

    private final ServiceProxy accessProxy;
    private final ServiceProxy evictionProxy;
    private final ProtocolEncryptionManager accessEncryptionManager;
    private final ProtocolEncryptionManager evictionEncryptionManager;
    private final SecureRandom random;
    private final int c;
    private final int leafCount;
    private final int treeHeight;
    private final int stashSize;
    private final int z;
    private final int s;
    private final int evictInterval;
    private final int bidSpace;
    private final int blockSize;
    private final GlobalMapEntry[] positionMap;
    private final LinkedBlockingQueue<EvictionTask> pendingEvictionTasks;
    private final Thread evictionWorker;
    private volatile boolean closed;

    public ConcurORAMManager(int clientId,
                             String serverIp,
                             int serverPort,
                             int c,
                             int treeHeight,
                             int stashSize,
                             int z,
                             int s,
                             int evictInterval,
                             int blockSize) {
        this.accessProxy = new ServiceProxy(clientId, MESSAGE_TYPE, serverIp, serverPort);
        this.evictionProxy = new ServiceProxy(clientId + 10_000, MESSAGE_TYPE, serverIp, serverPort);
        this.accessEncryptionManager = new ProtocolEncryptionManager();
        this.evictionEncryptionManager = new ProtocolEncryptionManager();
        this.random = new SecureRandom();
        this.c = c;
        this.treeHeight = treeHeight;
        this.leafCount = 1 << (treeHeight - 1);
        this.stashSize = stashSize;
        this.z = z;
        this.s = s;
        this.evictInterval = c <= 1 ? 1 : Math.max(1, Math.min(evictInterval, c));
        this.bidSpace = leafCount;
        this.blockSize = blockSize;
        this.positionMap = initializePositionMap();
        this.pendingEvictionTasks = new LinkedBlockingQueue<EvictionTask>();
        this.evictionWorker = new Thread(this::runEvictionWorker, "concur-eviction-" + clientId);
        this.evictionWorker.setDaemon(true);
        this.evictionWorker.start();
    }

    public byte[] access(Operation operation, int bid, byte[] dataStar) {
        if (bid < 0 || bid >= bidSpace) {
            throw new IllegalArgumentException("Invalid bid " + bid);
        }

        // 1. Register query
        ConcurMessages.RegisterQueryResponse registerResponse = registerQuery(bid);
        int queryId = registerResponse.getQueryId();
        int roundId = registerResponse.getRoundId();
        boolean firstInQueryLog = isFirstInQueryLog(bid, registerResponse.getPriorQl());

        // 2. Read logs and stashes
        ConcurMessages.LogsAndStashesResponse logs = readLogsAndStashes();
        ORAMBlock latestOutsidePath = latestForBid(
                bid,
                accessEncryptionManager.decryptBlocks(logs.getDrlSetBlocks(), blockSize),
                accessEncryptionManager.decryptBlocks(logs.getMainStashBlocks(), blockSize),
                accessEncryptionManager.decryptBlocks(logs.getStashSetBlocks(), blockSize)
        );

        // 3. Read path
        boolean readRealPath = firstInQueryLog && latestOutsidePath == null;
        int pathPid = readRealPath ? positionMap[bid].getPid() : random.nextInt(leafCount);
        ORAMBlock pathBlock = accessEncryptionManager.decryptBlock(
                readDataBlock(pathPid, buildPathSlotSelections(bid, pathPid, readRealPath)),
                blockSize
        );
        ORAMBlock latest = latestOutsidePath;
        if (readRealPath) {
            latest = newer(latest, pathBlock);
        }

        //4. Wait for current DRL
        byte[][] currentDrl = waitCurrentDrlPrefix(queryId);
        latest = newer(latest, latestForBid(
                bid,
                accessEncryptionManager.decryptBlocks(nonNullEntries(currentDrl), blockSize)
        ));
        if (latest == null) {
            throw new IllegalStateException("Cannot find latest block for bid " + bid);
        }
        byte[] oldData = Arrays.copyOf(latest.getData(), latest.getData().length);
        byte[] newData = operation == Operation.WRITE ? copyData(dataStar) : oldData;
        int newPid = latest.getPid();// random.nextInt(leafCount);
        int newSeq = latest.getSeq() + 1;
        GlobalMapEntry location = positionMap[bid];
        ORAMBlock blockUpdate = new ORAMBlock(bid, newData, newPid, newSeq, false);
        UpdateMapEntry mapUpdate = new UpdateMapEntry(
                bid,
                newPid,
                location.getLevel(),
                location.getSlot(),
                newSeq,
                false
        );

        // 5. Write query result
        writeQueryResult(
                queryId,
                accessEncryptionManager.encryptBlock(blockUpdate),
                accessEncryptionManager.encryptUpdateMapEntry(mapUpdate)
        );
        

        // 6. Evictions   
        boolean segmentEnd = ((queryId + 1) % evictInterval == 0) || queryId == c - 1;

        EvictionTask evictionTask = null;

        if (segmentEnd) {
            int fromQueryId = (queryId / evictInterval) * evictInterval;
            int toQueryId = queryId;
            evictionTask = new EvictionTask(roundId, fromQueryId, toQueryId);
        }

        applyRemoteMapUpdates(roundId);

        if (queryId == c - 1) {
            finalizeRound();
        }

        if (evictionTask != null) {
            if (c <= 1) {
                processEviction(evictionTask);
            } else {
                pendingEvictionTasks.offer(evictionTask);
            }
        }

        readAndApplyCommittedMapUpdates(roundId);

        return oldData;
    }

    private void runEvictionWorker() {
        while (!closed || !pendingEvictionTasks.isEmpty()) {
            try {
                EvictionTask task = pendingEvictionTasks.poll(100, TimeUnit.MILLISECONDS);
                if (task == null) {
                    continue;
                }
                processEviction(task);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                t.printStackTrace();
                closed = true;
                throw new RuntimeException("Eviction worker failed", t);
            }
        }
    }

    private void readAndApplyCommittedMapUpdates(int targetRoundId) {
        ConcurMessages.MapUpdatesResponse response = new ConcurMessages.MapUpdatesResponse();
        response.readExternal(
                sendAccess(
                        ServerOperationType.CONCUR_READ_COMMITTED_MAP_UPDATES,
                        new ConcurMessages.IntRequest(targetRoundId)
                ),
                0
        );

        if (response.getRoundId() != targetRoundId) {
            throw new IllegalStateException(
                    "Unexpected committed map round: expected=" + targetRoundId
                            + ", actual=" + response.getRoundId()
            );
        }

        applyCommittedMapUpdates(response.getEntries());
    }

    private void applyCommittedMapUpdates(byte[][] encryptedUpdates) {
        if (encryptedUpdates == null) {
            return;
        }

        for (byte[] encryptedUpdate : encryptedUpdates) {
            if (encryptedUpdate == null) {
                continue;
            }

            UpdateMapEntry update = accessEncryptionManager.decryptUpdateMapEntry(encryptedUpdate);
            if (update.isDummy() || update.getBid() < 0 || update.getBid() >= positionMap.length) {
                continue;
            }

            int bid = update.getBid();
            GlobalMapEntry old = positionMap[bid];

            if (old == null || update.getSeq() >= old.getSeq()) {
                positionMap[bid] = new GlobalMapEntry(
                        update.getPid(),
                        update.getLevel(),
                        update.getSlot(),
                        update.getSeq()
                );
            }
        }
    }

    private EvictionBuildResult buildEvictionPath(ORAMPath wotPath, ORAMBlock[] drlBlocks, ORAMBlock[] tempBlocks) {
        ORAMBucket[] rebuilt = new ORAMBucket[wotPath.length()];
        Map<Integer, PathSlot> pathSlots = new HashMap<Integer, PathSlot>();

        // 1. Copy WOT path as-is. Do not delete old blocks.
        for (int level = 0; level < wotPath.length(); level++) {
            ORAMBucket oldBucket = wotPath.getBucket(level);
            ORAMBlock[] blocks = new ORAMBlock[oldBucket.size()];

            for (int slot = 0; slot < oldBucket.size(); slot++) {
                ORAMBlock block = oldBucket.getBlock(slot);

                if (block == null || block.isDummy()) {
                    blocks[slot] = ORAMBlock.dummy(blockSize);
                    continue;
                }

                blocks[slot] = copyBlock(block);

                int bid = block.getBid();
                if (bid < 0 || bid >= positionMap.length) {
                    continue;
                }

                PathSlot recorded = pathSlots.get(bid);
                if (recorded == null) {
                    pathSlots.put(bid, new PathSlot(level, slot));
                } else {
                    ORAMBlock recordedBlock = rebuilt[recorded.level].getBlock(recorded.slot);
                    if (recordedBlock == null || recordedBlock.isDummy() || block.getSeq() > recordedBlock.getSeq()) {
                        pathSlots.put(bid, new PathSlot(level, slot));
                    }
                }
            }

            rebuilt[level] = new ORAMBucket(oldBucket.getBucketId(), blocks);
        }

        // 2. Rebuild temp stash from empty.
        ORAMBlock[] rebuiltTempStash = new ORAMBlock[stashSize];
        for (int i = 0; i < rebuiltTempStash.length; i++) {
            rebuiltTempStash[i] = ORAMBlock.dummy(blockSize);
        }

        // 3. Collect latest incoming blocks from DRL + old temp stash.
        Map<Integer, ORAMBlock> incomingLatest = new HashMap<Integer, ORAMBlock>();

        ORAMBlock[][] groups = new ORAMBlock[][] { drlBlocks, tempBlocks };
        for (ORAMBlock[] group : groups) {
            if (group == null) {
                continue;
            }

            for (ORAMBlock block : group) {
                if (block == null || block.isDummy()) {
                    continue;
                }

                int bid = block.getBid();
                if (bid < 0 || bid >= positionMap.length || positionMap[bid] == null) {
                    continue;
                }

                // Only keep the version matching the current main map seq.
                if (block.getSeq() < positionMap[bid].getSeq()) {
                    continue;
                }

                ORAMBlock old = incomingLatest.get(bid);
                if (old == null || block.getSeq() > old.getSeq()) {
                    incomingLatest.put(bid, copyBlock(block));
                }
            }
        }

        java.util.ArrayList<UpdateMapEntry> mapUpdates = new java.util.ArrayList<UpdateMapEntry>();

        // 4. Place incoming blocks:
        //    - If bid exists on WOT path, overwrite that original position.
        //    - Otherwise, put it into temp stash.
        //    - If temp stash is full, skip it.
        //    - Never change positionMap here.
        for (ORAMBlock block : incomingLatest.values()) {
            if (block == null || block.isDummy()) {
                continue;
            }

            int bid = block.getBid();
            if (bid < 0 || bid >= positionMap.length || positionMap[bid] == null) {
                continue;
            }

            PathSlot pathSlot = pathSlots.get(bid);

            if (pathSlot != null) {
                ORAMBlock placed = copyBlock(block);
                rebuilt[pathSlot.level].setBlock(pathSlot.slot, placed);

                GlobalMapEntry curMap = positionMap[bid];
                mapUpdates.add(new UpdateMapEntry(
                        bid,
                        curMap.getPid(),
                        curMap.getLevel(),
                        curMap.getSlot(),
                        curMap.getSeq(),
                        false
                ));

                continue;
            }

            boolean placedInStash = false;
            for (int i = 0; i < rebuiltTempStash.length; i++) {
                ORAMBlock cur = rebuiltTempStash[i];

                if (cur == null || cur.isDummy()) {
                    rebuiltTempStash[i] = copyBlock(block);

                    GlobalMapEntry curMap = positionMap[bid];
                    mapUpdates.add(new UpdateMapEntry(
                            bid,
                            curMap.getPid(),
                            curMap.getLevel(),
                            curMap.getSlot(),
                            curMap.getSeq(),
                            false
                    ));

                    placedInStash = true;
                    break;
                }
            }

            if (!placedInStash) {
                GlobalMapEntry curMap = positionMap[bid];
                mapUpdates.add(new UpdateMapEntry(
                        bid,
                        curMap.getPid(),
                        curMap.getLevel(),
                        curMap.getSlot(),
                        curMap.getSeq(),
                        false
                ));
            }
        }

        return new EvictionBuildResult(
                new ORAMPath(wotPath.getPid(), rebuilt),
                rebuiltTempStash,
                mapUpdates.toArray(new UpdateMapEntry[0])
        );
    }

    private byte[][] nonNullEntries(byte[][] entries) {
        if (entries == null) {
            return new byte[0][];
        }

        java.util.ArrayList<byte[]> out = new java.util.ArrayList<byte[]>();
        for (byte[] entry : entries) {
            if (entry != null) {
                out.add(entry);
            }
        }

        return out.toArray(new byte[0][]);
    }

    private GlobalMapEntry[] initializePositionMap() {
        InitGbMpResponse response = new InitGbMpResponse();
        response.readExternal(sendAccess(ServerOperationType.INIT_GBMP, new InitGbMpRequest()), 0);
        GlobalMapEntry[] decoded = new GlobalMapEntry[bidSpace];
        oram.structure.EncryptedGlobalMapEntry[] encryptedGbMp = response.getEncryptedGbMp();
        for (int bid = 0; bid < decoded.length; bid++) {
            if (bid >= encryptedGbMp.length || encryptedGbMp[bid] == null) {
                throw new IllegalStateException("Missing encrypted position map entry for bid " + bid);
            }
            decoded[bid] = accessEncryptionManager.decryptGlobalMapEntry(encryptedGbMp[bid]);
        }
        return decoded;
    }

    private ConcurMessages.RegisterQueryResponse registerQuery(int bid) {
        ConcurMessages.RegisterQueryRequest request =
                new ConcurMessages.RegisterQueryRequest(accessEncryptionManager.encryptInt(bid, random));
        ConcurMessages.RegisterQueryResponse response = new ConcurMessages.RegisterQueryResponse();
        response.readExternal(sendAccess(ServerOperationType.CONCUR_REGISTER_QUERY, request), 0);
        return response;
    }

    private ConcurMessages.LogsAndStashesResponse readLogsAndStashes() {
        ConcurMessages.LogsAndStashesResponse response = new ConcurMessages.LogsAndStashesResponse();
        response.readExternal(sendAccess(ServerOperationType.CONCUR_READ_LOGS_AND_STASHES, new ConcurMessages.Empty()), 0);
        return response;
    }

    private byte[] readDataBlock(int pid, int[] slots) {
        ConcurMessages.BlockResponse response = new ConcurMessages.BlockResponse();
        response.readExternal(
                sendAccess(ServerOperationType.CONCUR_READ_DATA_PATH, new ConcurMessages.ReadPathSlotRequest(pid, slots)),
                0
        );
        return response.getCiphertext();
    }

    private byte[][] waitCurrentDrlPrefix(int queryId) {
        ConcurMessages.ByteMatrixResponse response = new ConcurMessages.ByteMatrixResponse();
        response.readExternal(
                sendAccess(ServerOperationType.CONCUR_READ_CURRENT_DRL, new ConcurMessages.IntRequest(queryId)),
                0
        );
        return response.getEntries();
    }

    private void applyRemoteMapUpdates(int targetRoundId) {
        ConcurMessages.MapUpdatesResponse response = new ConcurMessages.MapUpdatesResponse();
        response.readExternal(
                sendAccess(ServerOperationType.CONCUR_READ_MAP_UPDATES, new ConcurMessages.IntRequest(targetRoundId)),
                0
        );

        if (response.getRoundId() != targetRoundId) {
            throw new IllegalStateException(
                    "Unexpected current map round: expected=" + targetRoundId
                            + ", actual=" + response.getRoundId()
            );
        }

        for (byte[] encryptedUpdate : response.getEntries()) {
            UpdateMapEntry update = accessEncryptionManager.decryptUpdateMapEntry(encryptedUpdate);
            if (!update.isDummy() && update.getBid() >= 0 && update.getBid() < positionMap.length) {
                int bid = update.getBid();
                GlobalMapEntry old = positionMap[bid];

                positionMap[bid] = new GlobalMapEntry(
                        update.getPid(),
                        old.getLevel(),
                        old.getSlot(),
                        update.getSeq()
                );
            }
        }
    }

    private void writeQueryResult(int queryId, byte[] encryptedBlock, byte[] encryptedMapUpdate) {
        sendAccess(
                ServerOperationType.CONCUR_WRITE_QUERY_RESULT,
                new ConcurMessages.WriteQueryResultRequest(queryId, encryptedBlock, encryptedMapUpdate)
        );
    }

    private ConcurMessages.FinalizeRoundResponse finalizeRound() {
        ConcurMessages.FinalizeRoundResponse response = new ConcurMessages.FinalizeRoundResponse();
        response.readExternal(sendAccess(ServerOperationType.CONCUR_FINALIZE_ROUND, new ConcurMessages.Empty()), 0);
        return response;
    }

    private void processEviction(EvictionTask task) {
        ConcurMessages.EvictionInputResponse input = new ConcurMessages.EvictionInputResponse();
        input.readExternal(
                sendEviction(
                        ServerOperationType.CONCUR_READ_EVICTION_INPUT,
                        new ConcurMessages.EvictionInputRequest(
                                task.roundId,
                                task.fromQueryId,
                                task.toQueryId
                        )
                ),
                0
        );

        ConcurMessages.EvictionCriticalResponse critical = new ConcurMessages.EvictionCriticalResponse();
        critical.readExternal(
                sendEviction(
                        ServerOperationType.CONCUR_READ_EVICTION_CRITICAL,
                        new ConcurMessages.IntRequest(input.getEvictionId())
                ),
                0
        );

        ORAMBlock[] drlBlocks = evictionEncryptionManager.decryptBlocks(
                nonNullEntries(input.getDrlBlocks()),
                blockSize
        );

        ORAMPath wotPath = decryptPath(mergeEvictionPath(
                input.getPid(),
                critical.getCriticalBuckets(),
                input.getNonCriticalBuckets()
        ));

        ORAMBlock[] tempBlocks = evictionEncryptionManager.decryptBlocks(
                nonNullEntries(critical.getTempStashBlocks()),
                blockSize
        );

        EvictionBuildResult result = buildEvictionPath(wotPath, drlBlocks, tempBlocks);

        sendEviction(ServerOperationType.CONCUR_SUBMIT_EVICTION, new ConcurMessages.SubmitEvictionRequest(
                input.getEvictionId(),
                evictionEncryptionManager.encryptPath(result.path),
                evictionEncryptionManager.encryptBlocks(result.tempStashBlocks),
                encryptUpdateMapEntries(result.mapUpdates)
        ));
    }

    private byte[][] encryptUpdateMapEntries(UpdateMapEntry[] updates) {
        if (updates == null) {
            return new byte[0][];
        }

        byte[][] out = new byte[updates.length][];
        for (int i = 0; i < updates.length; i++) {
            if (updates[i] == null) {
                out[i] = null;
            } else {
                out[i] = evictionEncryptionManager.encryptUpdateMapEntry(updates[i]);
            }
        }
        return out;
    }

    private int[] buildPathSlotSelections(int bid, int pid, boolean readRealPath) {
        int[] slots = new int[treeHeight];
        for (int level = 0; level < treeHeight; level++) {
            slots[level] = s <= 0 ? Math.max(0, z - 1) : z + random.nextInt(s);
        }
        if (readRealPath) {
            GlobalMapEntry entry = positionMap[bid];
            if (entry.getPid() == pid && entry.getLevel() >= 0 && entry.getLevel() < treeHeight) {
                slots[entry.getLevel()] = entry.getSlot();
            }
        }
        return slots;
    }




    private int choosePidForLevel(int pathPid, int level) {
        if (level <= 0) {
            return random.nextInt(leafCount);
        }

        int suffixBits = (treeHeight - 1) - level;
        int prefix = pathPid >> suffixBits;

        if (suffixBits == 0) {
            return pathPid;
        }

        int suffix = random.nextInt(1 << suffixBits);
        return (prefix << suffixBits) | suffix;
    }

    private ORAMBucket[] copyPathBuckets(ORAMPath path) {
        ORAMBucket[] copied = new ORAMBucket[path.length()];
        for (int level = 0; level < path.length(); level++) {
            ORAMBucket bucket = path.getBucket(level);
            ORAMBlock[] blocks = new ORAMBlock[bucket.size()];
            for (int slot = 0; slot < bucket.size(); slot++) {
                blocks[slot] = copyBlock(bucket.getBlock(slot));
            }
            copied[level] = new ORAMBucket(bucket.getBucketId(), blocks);
        }
        return copied;
    }

    private ORAMBlock[] initializeStash(ORAMBlock[] existing) {
        ORAMBlock[] stash = new ORAMBlock[stashSize];
        for (int i = 0; i < stash.length; i++) {
            stash[i] = ORAMBlock.dummy(blockSize);
        }
        if (existing == null) {
            return stash;
        }
        int limit = Math.min(existing.length, stash.length);
        for (int i = 0; i < limit; i++) {
            stash[i] = copyBlock(existing[i]);
        }
        return stash;
    }

    private Map<Integer, PathSlot> buildPathSlotIndex(ORAMPath path) {
        Map<Integer, PathSlot> slots = new HashMap<Integer, PathSlot>();
        for (int level = 0; level < path.length(); level++) {
            ORAMBucket bucket = path.getBucket(level);
            for (int slot = 0; slot < bucket.size(); slot++) {
                ORAMBlock block = bucket.getBlock(slot);
                if (block == null || block.isDummy()) {
                    continue;
                }
                slots.put(block.getBid(), new PathSlot(level, slot));
            }
        }
        return slots;
    }

    private Map<Integer, Integer> buildTempSlotIndex(ORAMBlock[] tempBlocks) {
        Map<Integer, Integer> slots = new HashMap<Integer, Integer>();
        for (int i = 0; i < tempBlocks.length; i++) {
            ORAMBlock block = tempBlocks[i];
            if (block == null || block.isDummy()) {
                continue;
            }
            slots.put(block.getBid(), i);
        }
        return slots;
    }

    private int firstDummySlot(ORAMBlock[] stash) {
        for (int i = 0; i < stash.length; i++) {
            ORAMBlock block = stash[i];
            if (block == null || block.isDummy()) {
                return i;
            }
        }
        return -1;
    }

    private void collectLatest(Map<Integer, ORAMBlock> latest, ORAMPath path) {
        for (ORAMBucket bucket : path.getBuckets()) {
            collectLatest(latest, bucket.getBlocks());
        }
    }

    private void collectLatest(Map<Integer, ORAMBlock> latest, ORAMBlock[] blocks) {
        for (ORAMBlock block : blocks) {
            if (block == null || block.isDummy()) {
                continue;
            }
            ORAMBlock existing = latest.get(block.getBid());
            if (existing == null || block.getSeq() > existing.getSeq()) {
                latest.put(block.getBid(), copyBlock(block));
            }
        }
    }

    private ORAMBlock latestForBid(int bid, ORAMBlock[]... groups) {
        ORAMBlock latest = null;
        for (ORAMBlock[] group : groups) {
            for (ORAMBlock block : group) {
                if (block != null && !block.isDummy() && block.getBid() == bid) {
                    latest = newer(latest, block);
                }
            }
        }
        return latest;
    }

    private ORAMBlock newer(ORAMBlock a, ORAMBlock b) {
        if (b == null || b.isDummy()) {
            return a;
        }
        if (a == null || a.isDummy() || b.getSeq() > a.getSeq()) {
            return copyBlock(b);
        }
        return a;
    }

    private boolean isFirstInQueryLog(int bid, byte[][] priorQl) {
        for (byte[] encryptedAddr : priorQl) {
            if (encryptedAddr != null && accessEncryptionManager.decryptInt(encryptedAddr) == bid) {
                return false;
            }
        }
        return true;
    }

    private ORAMPath decryptPath(EncryptedORAMPath encryptedPath) {
        EncryptedORAMBucket[] encryptedBuckets = encryptedPath.getBuckets();
        ORAMBucket[] buckets = new ORAMBucket[encryptedBuckets.length];
        for (int level = 0; level < encryptedBuckets.length; level++) {
            byte[][] encryptedBlocks = encryptedBuckets[level].getEncryptedBlocks();
            ORAMBlock[] blocks = new ORAMBlock[encryptedBlocks.length];
            for (int i = 0; i < encryptedBlocks.length; i++) {
                blocks[i] = encryptedBlocks[i] == null
                        ? ORAMBlock.dummy(blockSize)
                        : evictionEncryptionManager.decryptBlock(encryptedBlocks[i], blockSize);
            }
            buckets[level] = new ORAMBucket(encryptedBuckets[level].getBucketId(), blocks);
        }
        return new ORAMPath(encryptedPath.getPid(), buckets);
    }

    private EncryptedORAMPath mergeEvictionPath(int pid,
                                                EncryptedORAMBucket[] criticalBuckets,
                                                EncryptedORAMBucket[] nonCriticalBuckets) {
        EncryptedORAMBucket[] buckets = new EncryptedORAMBucket[treeHeight];
        int level = 0;
        for (EncryptedORAMBucket bucket : criticalBuckets) {
            buckets[level++] = bucket;
        }
        for (EncryptedORAMBucket bucket : nonCriticalBuckets) {
            buckets[level++] = bucket;
        }
        return new EncryptedORAMPath(pid, buckets);
    }

    private byte[] sendAccess(ServerOperationType operation, RawCustomExternalizable request) {
        return send(accessProxy, operation, request);
    }

    private byte[] sendEviction(ServerOperationType operation, RawCustomExternalizable request) {
        return send(evictionProxy, operation, request);
    }

    private byte[] send(ServiceProxy proxy, ServerOperationType operation, RawCustomExternalizable request) {
        Message message = proxy.sendMessage(ORAMUtils.serializeRequest(operation, request));
        if (message == null || message.getSerializedMessage() == null) {
            throw new IllegalStateException("No response for operation " + operation);
        }
        byte[] payload = message.getSerializedMessage();
        if (payload.length == 0
                && operation != ServerOperationType.CONCUR_WRITE_QUERY_RESULT
                && operation != ServerOperationType.CONCUR_SUBMIT_EVICTION) {
            throw new IllegalStateException("Empty response for operation " + operation);
        }
        return payload;
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

    private byte[] copyData(byte[] data) {
        byte[] source = data == null ? new byte[0] : data;
        byte[] out = new byte[blockSize];
        System.arraycopy(source, 0, out, 0, Math.min(source.length, out.length));
        return out;
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting", e);
        }
    }

    public void close() {
        closed = true;
        evictionWorker.interrupt();

        try {
            evictionWorker.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        accessProxy.close();
        evictionProxy.close();

    }

    private static class EvictionBuildResult {
        private final ORAMPath path;
        private final ORAMBlock[] tempStashBlocks;
        private final UpdateMapEntry[] mapUpdates;

        private EvictionBuildResult(
                ORAMPath path,
                ORAMBlock[] tempStashBlocks,
                UpdateMapEntry[] mapUpdates
        ) {
            this.path = path;
            this.tempStashBlocks = tempStashBlocks;
            this.mapUpdates = mapUpdates;
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

    private static class PathSlot {
        private final int level;
        private final int slot;

        private PathSlot(int level, int slot) {
            this.level = level;
            this.slot = slot;
        }
    }
}
