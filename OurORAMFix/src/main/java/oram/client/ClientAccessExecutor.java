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

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class ClientAccessExecutor {
    private static final Logger logger = LoggerFactory.getLogger("benchmarking");
    private static final AtomicLong accessCount = new AtomicLong();
    private static final AtomicLong totalAccessNs = new AtomicLong();
    private static final AtomicLong totalRound1PhaseNs = new AtomicLong();
    private static final AtomicLong totalRound2PhaseNs = new AtomicLong();
    private static final AtomicLong totalRound3PhaseNs = new AtomicLong();
    private static final AtomicLong totalRound1RpcNs = new AtomicLong();
    private static final AtomicLong totalUdMpApplyNs = new AtomicLong();
    private static final AtomicLong totalDelayedReadNs = new AtomicLong();
    private static final AtomicLong totalRound2RpcNs = new AtomicLong();
    private static final AtomicLong totalRound3UpdateRpcNs = new AtomicLong();
    private static final AtomicLong totalEvictionBuildNs = new AtomicLong();
    private static final AtomicLong totalBarrierWaitNs = new AtomicLong();
    private static final AtomicLong totalRound3EvictionRpcNs = new AtomicLong();
    private static final AtomicLong totalUdMpVersions = new AtomicLong();
    private static final AtomicLong maxUdMpVersions = new AtomicLong();
    private static final AtomicLong totalPriorQlEntries = new AtomicLong();
    private static final AtomicLong maxPriorQlEntries = new AtomicLong();
    private static final AtomicBoolean shutdownHookRegistered = new AtomicBoolean(false);
    private static final AtomicBoolean timingSummaryLogged = new AtomicBoolean(false);

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
    private final TimestepBarrier round2ReadBarrier;
    private int lastAppliedTimestep;

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
                                TimestepBarrier round2ReadBarrier) {
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
        this.round2Helper = new ClientRound2Helper(leafCount, blockSize);
        this.evictionPlanner = new EvictionPlanner(leafCount, treeHeight, c, bidSpace);
        this.evictionHelper = new ClientEvictionHelper(treeHeight, rootBucketSize, competitionBucketSize, bucketSize, blockSize, c, leafCount);
        this.encryptionManager = new ProtocolEncryptionManager();
        this.localGbMp = manager.initializeGbMp(bidSpace);
        this.round2ReadBarrier = round2ReadBarrier;
        this.lastAppliedTimestep = -1;
        
        registerShutdownHookOnce();
    }

    public ClientAccessResult access(Operation op,
                                     int bid,
                                     byte[] dataStar,
                                     DelayedRequest reqPrev) {
        long accessStart = System.nanoTime();
        // Submit (op, bid) and receive the response (seq, QL, LastRL, GbMp, LastUdMp)
        long round1PhaseStart = System.nanoTime();
        int delayedTimestep = reqPrev == null ? -1 : reqPrev.getSeq() / c;
        long round1RpcStart = System.nanoTime();
        Round1Context context = round1Decoder.decode(
                manager.submitRound1Query(op, bid, lastAppliedTimestep, delayedTimestep)
        );
        long round1RpcEnd = System.nanoTime();
        int udMpVersionCount = context.getUdMpHistory().size();
        int priorQlEntryCount = context.getPriorRequests().size();
        long udMpApplyStart = System.nanoTime();
        applyUdMpHistoryToLocalGbMp(context.getUdMpHistory());
        long udMpApplyEnd = System.nanoTime();
        lastAppliedTimestep = Math.max(lastAppliedTimestep, context.getLatestUdMpTimestep());
        // Access the data result of the last timestep query
        byte[] dataPrev = null;
        long delayedReadStart = System.nanoTime();
        if (reqPrev != null) {
            dataPrev = round2Helper.readDelayed(reqPrev, context.getDelayedRL());
        }
        long delayedReadEnd = System.nanoTime();
        long round1PhaseEnd = System.nanoTime();

        long round2PhaseStart = System.nanoTime();
        ReadDecision readDecision = round2Helper.decidePath(bid, context, localGbMp);
        EvictionAssignment assignment = evictionPlanner.plan(context.getSeq());
        long round2RpcStart = System.nanoTime();
        ORAMManager.Round2PathPair pathPair = manager.readRound2PathPair(
                readDecision.getPid(),
                assignment.getPidE()
        );
        long round2RpcEnd = System.nanoTime();
        ORAMPath pathCur = pathPair.getFirstPath();
        ORAMPath pathE = pathPair.getSecondPath();
        ExtractCurResult extractCurResult = round2Helper.extractCur(bid, context.getSeq(), pathCur, context);
        long round2PhaseEnd = System.nanoTime();

        long round3PhaseStart = System.nanoTime();
        BuildUpdResult buildUpdResult = round2Helper.buildUpd(
                op,
                bid,
                dataStar,
                extractCurResult.getDataCur(),
                context.getSeq(),
                c,
                blockSize
        );
        long round3UpdateRpcStart = System.nanoTime();
        manager.submitRound3UpdateCur(
                context.getSeq(),
                encryptionManager.encryptRLSlot(buildUpdResult.getSlotCur()),
                encryptionManager.encryptUpdateMapEntry(buildUpdResult.getMpCur())
        );
        long round3UpdateRpcEnd = System.nanoTime();
        long evictionBuildStart = System.nanoTime();
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
        long evictionBuildEnd = System.nanoTime();
        long barrierStart = System.nanoTime();
        awaitRound2ReadBarrier(context.getTimestep());
        long barrierEnd = System.nanoTime();
        long round3EvictionRpcStart = System.nanoTime();
        manager.submitRound3Eviction(
                context.getSeq(),
                evictionResult.getPathPrime().getPid(),
                encryptedSegments
        );
        long round3EvictionRpcEnd = System.nanoTime();
        long round3PhaseEnd = System.nanoTime();

        long accessEnd = System.nanoTime();

        accessCount.incrementAndGet();
        totalAccessNs.addAndGet(accessEnd - accessStart);
        totalRound1PhaseNs.addAndGet(round1PhaseEnd - round1PhaseStart);
        totalRound2PhaseNs.addAndGet(round2PhaseEnd - round2PhaseStart);
        totalRound3PhaseNs.addAndGet(round3PhaseEnd - round3PhaseStart);
        totalRound1RpcNs.addAndGet(round1RpcEnd - round1RpcStart);
        totalUdMpApplyNs.addAndGet(udMpApplyEnd - udMpApplyStart);
        totalDelayedReadNs.addAndGet(delayedReadEnd - delayedReadStart);
        totalRound2RpcNs.addAndGet(round2RpcEnd - round2RpcStart);
        totalRound3UpdateRpcNs.addAndGet(round3UpdateRpcEnd - round3UpdateRpcStart);
        totalEvictionBuildNs.addAndGet(evictionBuildEnd - evictionBuildStart);
        totalBarrierWaitNs.addAndGet(barrierEnd - barrierStart);
        totalRound3EvictionRpcNs.addAndGet(round3EvictionRpcEnd - round3EvictionRpcStart);
        totalUdMpVersions.addAndGet(udMpVersionCount);
        updateMax(maxUdMpVersions, udMpVersionCount);
        totalPriorQlEntries.addAndGet(priorQlEntryCount);
        updateMax(maxPriorQlEntries, priorQlEntryCount);

        return new ClientAccessResult(
                dataPrev,
                extractCurResult.getDataCur(),
                extractCurResult.getDelayedRequest()
        );
    }

    public long getResidentClientStorageBytes() {
        return (long) localGbMp.length * Integer.BYTES * 2;
    }

    private void awaitRound2ReadBarrier(int timestep) {
        if (round2ReadBarrier != null) {
            try {
                round2ReadBarrier.await(timestep);
            } catch (Exception e) {
                throw new IllegalStateException("Round2 barrier failed for timestep " + timestep, e);
            }
        }
    }

    private void applyUdMpHistoryToLocalGbMp(java.util.List<UpdateMapEntry[]> history) {
        for (UpdateMapEntry[] version : history) {
            for (UpdateMapEntry entry : version) {
                if (entry == null || entry.isDummy()) {
                    continue;
                }
                int bid = entry.getBid();
                if (bid >= 0 && bid < localGbMp.length) {
                    localGbMp[bid] = new GlobalMapEntry(entry.getPid(), entry.getSeq());
                }
            }
        }
    }

    private static double nanosToMs(long nanos) {
        return nanos / 1_000_000.0;
    }

    private static void updateMax(AtomicLong target, long value) {
        long current;
        do {
            current = target.get();
            if (value <= current) {
                return;
            }
        } while (!target.compareAndSet(current, value));
    }

    public static void resetTimingStats() {
        accessCount.set(0L);
        totalAccessNs.set(0L);
        totalRound1PhaseNs.set(0L);
        totalRound2PhaseNs.set(0L);
        totalRound3PhaseNs.set(0L);
        totalRound1RpcNs.set(0L);
        totalUdMpApplyNs.set(0L);
        totalDelayedReadNs.set(0L);
        totalRound2RpcNs.set(0L);
        totalRound3UpdateRpcNs.set(0L);
        totalEvictionBuildNs.set(0L);
        totalBarrierWaitNs.set(0L);
        totalRound3EvictionRpcNs.set(0L);
        totalUdMpVersions.set(0L);
        maxUdMpVersions.set(0L);
        totalPriorQlEntries.set(0L);
        maxPriorQlEntries.set(0L);
        timingSummaryLogged.set(false);
    }

    public static void logTimingSummary() {
        if (!timingSummaryLogged.compareAndSet(false, true)) {
            return;
        }
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
        logger.info(
                "access diagnostic summary avgRound1RpcMs={} avgUdMpApplyMs={} avgDelayedReadMs={} " +
                        "avgRound2RpcMs={} avgRound3UpdateRpcMs={} avgEvictionBuildMs={} " +
                        "avgBarrierWaitMs={} avgRound3EvictionRpcMs={} avgUdMpVersions={} maxUdMpVersions={} " +
                        "avgPriorQlEntries={} maxPriorQlEntries={}",
                nanosToMs(totalRound1RpcNs.get() / count),
                nanosToMs(totalUdMpApplyNs.get() / count),
                nanosToMs(totalDelayedReadNs.get() / count),
                nanosToMs(totalRound2RpcNs.get() / count),
                nanosToMs(totalRound3UpdateRpcNs.get() / count),
                nanosToMs(totalEvictionBuildNs.get() / count),
                nanosToMs(totalBarrierWaitNs.get() / count),
                nanosToMs(totalRound3EvictionRpcNs.get() / count),
                ((double) totalUdMpVersions.get()) / count,
                maxUdMpVersions.get(),
                ((double) totalPriorQlEntries.get()) / count,
                maxPriorQlEntries.get()
        );
    }

    private static void registerShutdownHookOnce() {
        if (!shutdownHookRegistered.compareAndSet(false, true)) {
            return;
        }

        Runtime.getRuntime().addShutdownHook(new Thread(
                ClientAccessExecutor::logTimingSummary,
                "client-access-timing-summary"
        ));
    }
}
