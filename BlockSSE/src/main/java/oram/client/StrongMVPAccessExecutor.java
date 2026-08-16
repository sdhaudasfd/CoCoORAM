package oram.client;

import oram.messages.TurnUpdateResponse;
import oram.security.ProtocolEncryptionManager;
import oram.structure.EncryptedMapUpdate;
import oram.structure.EncryptedORAMPath;
import oram.structure.GlobalMapEntry;
import oram.structure.MapUpdate;
import oram.structure.ORAMBlock;
import oram.structure.ORAMBucket;
import oram.structure.ORAMPath;
import oram.utils.Operation;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;

public class StrongMVPAccessExecutor {
    private final StrongMVPManager manager;
    private final int c;
    private final int leafCount;
    private final int treeHeight;
    private final int rootBucketSize;
    private final int bucketSize;
    private final int blockSize;
    private final int bidSpace;
    private final ProtocolEncryptionManager encryptionManager;
    private final SecureRandom random;
    private GlobalMapEntry[] positionMap;

    public StrongMVPAccessExecutor(StrongMVPManager manager,
                                   int c,
                                   int leafCount,
                                   int treeHeight,
                                   int rootBucketSize,
                                   int bucketSize,
                                   int blockSize,
                                   int bidSpace) {
        this.manager = manager;
        this.c = c;
        this.leafCount = leafCount;
        this.treeHeight = treeHeight;
        this.rootBucketSize = rootBucketSize;
        this.bucketSize = bucketSize;
        this.blockSize = blockSize;
        this.bidSpace = bidSpace;
        this.encryptionManager = new ProtocolEncryptionManager();
        this.random = new SecureRandom();
        this.positionMap = manager.initializePositionMap(bidSpace);
    }

    public byte[] access(Operation op, int bid, byte[] dataStar, CyclicBarrier stepBarrier) throws Exception {
        int seq = manager.registerAccess();
        int slot = seq % c;
        int roundBaseSeq = seq - slot;
        byte[] data = null;

        for (int turn = 0; turn < c; turn++) {
            int turnSeq = roundBaseSeq + turn;

            if (turn == slot) {
                TurnResult result = doRealTurn(seq, op, bid, dataStar);
                data = result.data;
                TurnUpdateResponse update = manager.submitTurn(
                        seq,
                        result.encryptedPath,
                        result.encryptedMapUpdate
                );
                applyUpdate(update);
            } else {
                doDummyTurn();
                TurnUpdateResponse update = manager.waitTurnUpdate(turnSeq);
                applyUpdate(update);
            }
            if (stepBarrier != null) {
                stepBarrier.await();
            }
        }

        return data;
    }

    private void doDummyTurn() {
        int dummyPid = random.nextInt(leafCount);
        EncryptedORAMPath dummyPath = manager.readPath(dummyPid);
        manager.submitDummyTurn(dummyPath);
    }

    private TurnResult doRealTurn(int seq, Operation op, int bid, byte[] dataStar) {
        int oldPid = positionMap[bid].getPid();
        ORAMPath path = encryptionManager.decryptPath(manager.readPath(oldPid));
        ORAMBlock target = path.findLatestBlock(bid);
        if (target == null) {
            throw new IllegalStateException("Cannot find block " + bid + " on path " + oldPid);
        }

        byte[] oldData = Arrays.copyOf(target.getData(), target.getData().length);
        byte[] newData = op == Operation.WRITE ? Arrays.copyOf(dataStar, dataStar.length) : oldData;

        int newPid = random.nextInt(leafCount);
        ORAMBlock updated = new ORAMBlock(bid, newData, newPid, seq, false);
        ORAMPath rebuilt = rebuildPath(oldPid, path, updated);
        if (rebuilt == null) {
            newPid = oldPid;
            updated = new ORAMBlock(bid, newData, newPid, seq, false);
            rebuilt = rebuildPath(oldPid, path, updated);
        }
        if (rebuilt == null) {
            rebuilt = path;
            newPid = oldPid;
        }

        GlobalMapEntry entry = new GlobalMapEntry(newPid, seq);
        positionMap[bid] = entry;
        EncryptedORAMPath encryptedPath = encryptionManager.encryptPath(rebuilt);
        EncryptedMapUpdate encryptedUpdate = manager.encryptMapUpdate(new MapUpdate(bid, newPid, seq));
        return new TurnResult(oldData, encryptedPath, encryptedUpdate);
    }

    private ORAMPath rebuildPath(int pathPid, ORAMPath path, ORAMBlock updated) {
        Map<Integer, ORAMBlock> latestBlocks = new HashMap<>();
        for (ORAMBucket bucket : path.getBuckets()) {
            for (ORAMBlock block : bucket.getBlocks()) {
                if (block == null || block.isDummy()) {
                    continue;
                }
                ORAMBlock existing = latestBlocks.get(block.getBid());
                if (existing == null || block.getSeq() > existing.getSeq()) {
                    latestBlocks.put(block.getBid(), copyBlock(block));
                }
            }
        }
        latestBlocks.put(updated.getBid(), copyBlock(updated));

        ORAMBucket[] rebuilt = new ORAMBucket[path.length()];
        for (int level = 0; level < rebuilt.length; level++) {
            int size = level == 0 ? rootBucketSize : bucketSize;
            rebuilt[level] = new ORAMBucket(path.getBucket(level).getBucketId(), size, blockSize);
        }

        for (ORAMBlock block : latestBlocks.values()) {
            if (!placeOnPathBottomUp(rebuilt, pathPid, block)) {
                return null;
            }
        }

        return new ORAMPath(pathPid, rebuilt);
    }

    private boolean placeOnPathBottomUp(ORAMBucket[] buckets, int pathPid, ORAMBlock block) {
        int deepest = deepestCommonLevel(pathPid, block.getPid());
        for (int level = deepest; level >= 0; level--) {
            ORAMBucket bucket = buckets[level];
            for (int slot = 0; slot < bucket.size(); slot++) {
                if (bucket.getBlock(slot).isDummy()) {
                    bucket.setBlock(slot, copyBlock(block));
                    return true;
                }
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

    private void applyUpdate(TurnUpdateResponse update) {
        MapUpdate mapUpdate = manager.decryptMapUpdate(update.getEncryptedMapUpdate());
        int bid = mapUpdate.getBid();
        if (bid < 0 || bid >= positionMap.length) {
            throw new IllegalStateException("Invalid update bid " + bid);
        }
        positionMap[bid] = new GlobalMapEntry(mapUpdate.getPid(), mapUpdate.getSeq());
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

    private static class TurnResult {
        private final byte[] data;
        private final EncryptedORAMPath encryptedPath;
        private final EncryptedMapUpdate encryptedMapUpdate;

        private TurnResult(byte[] data,
                           EncryptedORAMPath encryptedPath,
                           EncryptedMapUpdate encryptedMapUpdate) {
            this.data = data;
            this.encryptedPath = encryptedPath;
            this.encryptedMapUpdate = encryptedMapUpdate;
        }
    }
}
