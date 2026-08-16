package oram.client;

import oram.security.ProtocolEncryptionManager;
import oram.client.module.ClientAccessResult;
import oram.client.module.DelayedRequest;
import oram.client.module.Round1Context;
import oram.structure.EncryptedBucketSegment;
import oram.structure.GlobalMapEntry;
import oram.structure.ORAMPath;
import oram.structure.UpdateMapEntry;
import oram.utils.Operation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Phaser;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.security.SecureRandom;

public class ClientAccessExecutor {
    private static final Logger logger = LoggerFactory.getLogger("benchmarking");
    private static final AtomicLong accessCount = new AtomicLong();
    private static final AtomicLong totalAccessNs = new AtomicLong();
    private static final AtomicLong totalRound1PhaseNs = new AtomicLong();
    private static final AtomicLong totalRound2PhaseNs = new AtomicLong();
    private static final AtomicLong totalRound3PhaseNs = new AtomicLong();
    private static final AtomicBoolean shutdownHookRegistered = new AtomicBoolean(false);

    private final ORAMManager manager;
    private final int c;
    private final int leafCount;
    private final int treeHeight;
    private final int rootBucketSize;
    private final int competitionBucketSize;
    private final int bucketSize;
    private final int bidSpace;
    private final int blockSize;
    private final Round1ContextDecoder round1Decoder;
    private final ClientRound2Helper round2Helper;
    private final EvictionPlanner evictionPlanner;
    private final ClientEvictionHelper evictionHelper;
    private final ProtocolEncryptionManager encryptionManager;
    private final GlobalMapEntry[] localGbMp;
    private final Phaser round2ReadPhaser;
    private final SecureRandom random = new SecureRandom();
    private volatile BidPositionListener positionListener;

    public ClientAccessExecutor(ORAMManager manager,
                                int c,
                                int leafCount,
                                int treeHeight,
                                int rootBucketSize,
                                int competitionBucketSize,
                                int bucketSize,
                                int bidSpace,
                                int blockSize) {
        this(manager, c, leafCount, treeHeight, rootBucketSize, competitionBucketSize, bucketSize, bidSpace, blockSize, null);
    }

    public ClientAccessExecutor(ORAMManager manager,
                                int c,
                                int leafCount,
                                int treeHeight,
                                int rootBucketSize,
                                int competitionBucketSize,
                                int bucketSize,
                                int bidSpace,
                                int blockSize,
                                Phaser round2ReadPhaser) {
        this.manager = manager;
        this.c = c;
        this.leafCount = leafCount;
        this.treeHeight = treeHeight;
        this.rootBucketSize = rootBucketSize;
        this.competitionBucketSize = competitionBucketSize;
        this.bucketSize = bucketSize;
        this.bidSpace = bidSpace;
        this.blockSize = blockSize;
        this.round1Decoder = new Round1ContextDecoder();
        this.round2Helper = new ClientRound2Helper(leafCount);
        this.evictionPlanner = new EvictionPlanner(leafCount, treeHeight, c, bidSpace);
        this.evictionHelper = new ClientEvictionHelper(treeHeight, rootBucketSize, competitionBucketSize, bucketSize, blockSize, c, leafCount);
        this.encryptionManager = new ProtocolEncryptionManager();
        this.localGbMp = manager.initializeGbMp(bidSpace);
        this.round2ReadPhaser = round2ReadPhaser;

        registerShutdownHookOnce();
    }

    public ClientAccessResult access(Operation op,
                                     int bid,
                                     byte[] dataStar,
                                     DelayedRequest reqPrev) {
        return access(op, bid, dataStar, reqPrev, -1, -1, null);
    }

    public ClientAccessResult access(Operation op,
                                     int bid,
                                     byte[] dataStar,
                                     DelayedRequest reqPrev,
                                     int explicitReadPid,
                                     int forcedWritePid,
                                     AccessDataTransformer transformer) {
        long accessStart = System.nanoTime();
        // Submit (op, bid) and receive the response (seq, QL, LastRL, GbMp, LastUdMp)
        long round1PhaseStart = System.nanoTime();
        Round1Context context = round1Decoder.decode(manager.submitRound1Query(op, bid));
        applyLastUdMpToLocalGbMp(context.getLastUdMp());
        // Access the data result of the last timestep query
        byte[] dataPrev = null;
        if (reqPrev != null) {
            dataPrev = round2Helper.readDelayed(reqPrev, context.getLastRL());
        }
        long round1PhaseEnd = System.nanoTime();

        long round2PhaseStart = System.nanoTime();
        ReadDecision readDecision = round2Helper.decidePath(
                bid,
                explicitReadPid,
                context,
                localGbMp
        );
        ORAMPath pathCur = manager.readRound2Path(readDecision.getPid());
        EvictionAssignment assignment = evictionPlanner.plan(context.getSeq());
        ORAMPath pathE = manager.readRound2Path(assignment.getPidE());
        int round2ReadPhase = arriveRound2ReadPhaser();
        ExtractCurResult extractCurResult = round2Helper.extractCur(bid, context.getSeq(), pathCur, context);
        long round2PhaseEnd = System.nanoTime();

        long round3PhaseStart = System.nanoTime();
        byte[] currentData = extractCurResult.getDataCur();
        byte[] writeData = dataStar;
        if (currentData != null && transformer != null) {
            writeData = transformer.transform(currentData);
        } else if (op == Operation.READ) {
            writeData = currentData;
        }
        BuildUpdResult buildUpdResult = round2Helper.buildUpd(
                op,
                bid,
                writeData,
                currentData == null ? null : writeData,
                context.getSeq(),
                c,
                blockSize,
                forcedWritePid
        );
        manager.submitRound3UpdateCur(
                context.getSeq(),
                encryptionManager.encryptRLSlot(buildUpdResult.getSlotCur()),
                encryptionManager.encryptUpdateMapEntry(buildUpdResult.getMpCur())
        );
        BuildEvictionResult evictionResult = evictionHelper.buildEviction(
                pathE,
                context.getLastRL(),
                localGbMp,
                assignment
        );
        EncryptedBucketSegment[] encryptedSegments = encryptionManager.encryptPathSegments(
                evictionResult.getPathPrime(),
                context.getSeq(),
                assignment.getSlot(),
                c,
                treeHeight,
                rootBucketSize,
                competitionBucketSize,
                bucketSize,
                leafCount
        );
        awaitRound2ReadPhaser(round2ReadPhase);
        manager.submitRound3Eviction(
                context.getSeq(),
                evictionResult.getPathPrime().getPid(),
                encryptedSegments
        );
        long round3PhaseEnd = System.nanoTime();

        long accessEnd = System.nanoTime();

        accessCount.incrementAndGet();
        totalAccessNs.addAndGet(accessEnd - accessStart);
        totalRound1PhaseNs.addAndGet(round1PhaseEnd - round1PhaseStart);
        totalRound2PhaseNs.addAndGet(round2PhaseEnd - round2PhaseStart);
        totalRound3PhaseNs.addAndGet(round3PhaseEnd - round3PhaseStart);

        return new ClientAccessResult(
                dataPrev,
                extractCurResult.getDataCur(),
                extractCurResult.getDelayedRequest(),
                buildUpdResult.getMpCur().isDummy() ? -1 : buildUpdResult.getMpCur().getPid()
        );
    }

    public long getResidentClientStorageBytes() {
        return (long) localGbMp.length * Integer.BYTES * 2;
    }

    public int getCurrentPid(int bid) {
        if (bid < 0 || bid >= localGbMp.length || localGbMp[bid] == null) {
            throw new IllegalArgumentException("No current position for bid " + bid);
        }
        return localGbMp[bid].getPid();
    }

    public int generateRandomPid() {
        return random.nextInt(leafCount);
    }

    public void setPositionListener(BidPositionListener positionListener) {
        this.positionListener = positionListener;
    }

    private int arriveRound2ReadPhaser() {
        return round2ReadPhaser == null ? -1 : round2ReadPhaser.arrive();
    }

    private void awaitRound2ReadPhaser(int phase) {
        if (round2ReadPhaser != null) {
            round2ReadPhaser.awaitAdvance(phase);
        }
    }

    private void applyLastUdMpToLocalGbMp(UpdateMapEntry[] lastUdMp) {
        for (UpdateMapEntry entry : lastUdMp) {
            if (entry == null || entry.isDummy()) {
                continue;
            }
            int bid = entry.getBid();
            if (bid >= 0 && bid < localGbMp.length) {
                localGbMp[bid] = new GlobalMapEntry(entry.getPid(), entry.getSeq());
                BidPositionListener listener = positionListener;
                if (listener != null) {
                    listener.onPositionUpdated(bid, entry.getPid());
                }
            }
        }
    }

    private static double nanosToMs(long nanos) {
        return nanos / 1_000_000.0;
    }

    private static void registerShutdownHookOnce() {
        if (!shutdownHookRegistered.compareAndSet(false, true)) {
            return;
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            long count = accessCount.get();
            if (count == 0L) {
                logger.info("access timing summary: no completed access calls");
                return;
            }

            logger.info(
                    "access timing summary count={} avgTotalMs={} avgRound1PhaseMs={} avgRound2PhaseMs={} avgRound3PhaseMs={}",
                    count,
                    nanosToMs(totalAccessNs.get() / count),
                    nanosToMs(totalRound1PhaseNs.get() / count),
                    nanosToMs(totalRound2PhaseNs.get() / count),
                    nanosToMs(totalRound3PhaseNs.get() / count)
            );
        }, "client-access-timing-summary"));
    }
}
