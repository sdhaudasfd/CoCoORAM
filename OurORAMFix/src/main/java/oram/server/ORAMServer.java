package oram.server;

import oram.messages.InitGbMpRequest;
import oram.messages.InitGbMpResponse;
import oram.messages.Round2ReadPathRequest;
import oram.messages.Round2ReadPathPairRequest;
import oram.messages.Round2ReadPathPairResponse;
import oram.messages.Round2ReadPathResponse;
import oram.structure.EncryptedORAMPath;
import oram.structure.EncryptedORAMBucket;
import oram.messages.Round1QueryRequest;
import oram.messages.Round1QueryResponse;
import oram.messages.Round3UpdateCurRequest;
import oram.messages.Round3UpdateCurResponse;
import oram.messages.Round3SubmitEvictionRequest;
import oram.messages.Round3SubmitEvictionResponse;
import oram.utils.ServerOperationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

public class ORAMServer extends ServerExecutable {
    private static final Logger logger = LoggerFactory.getLogger("oram");

    private final int c;
    private final ORAMState oramState;

    private int nextSeq;
    private int currentTimestep;
    private final List<byte[]> currentEncryptedQL;
    private final Object round1Lock = new Object();
    private final ArrayDeque<PendingRound1Request> pendingRound1Requests;
    private final boolean[] completedEvictionSlots;
    private int admittedInCurrentTimestep;
    private int completedEvictionsInCurrentTimestep;
    private boolean round1AdmissionOpen;
    private long admittedRound1Count;
    private long completedEvictionCount;
    private long totalRound1QueueWaitNs;
    private long maxRound1QueueWaitNs;
    private long totalRound1BuildNs;
    private long totalRound1ResponseBytes;
    private long totalReturnedUdMpVersions;
    private int maxReturnedUdMpVersions;
    private int maxPendingRound1Requests;
    private long timestepAdmissionClosedNs;
    private long firstEvictionCompletedNs;
    private long totalTimestepActiveNs;
    private long totalEvictionTailNs;
    private long maxEvictionTailNs;
    private long completedTimestepCount;

    public ORAMServer(int totalClients,
                      int c,
                      int bidExponent,
                      int rootBucketSize,
                      int competitionBucketSize,
                      int bucketSize,
                      int blockSize,
                      String ip,
                      int port) throws InterruptedException {
        super(0, ip, port);

        if (c <= 0) {
            throw new IllegalArgumentException("c must be positive");
        }
        if (totalClients < c) {
            throw new IllegalArgumentException("totalClients must be no smaller than c");
        }
        if (bidExponent <= 0 || bidExponent >= 31) {
            throw new IllegalArgumentException("bidExponent must be in [1, 30]");
        }

        int treeHeight = bidExponent + 1;

        int bidSpace = 1 << bidExponent;

        this.c = c;
        int historyVersions = (totalClients + c - 1) / c + 1;
        this.oramState = new ORAMState(
                c,
                treeHeight,
                rootBucketSize,
                competitionBucketSize,
                bucketSize,
                blockSize,
                bidSpace,
                historyVersions
        );
        this.nextSeq = 0;
        this.currentTimestep = 0;
        this.currentEncryptedQL = new ArrayList<>();
        this.pendingRound1Requests = new ArrayDeque<>();
        this.completedEvictionSlots = new boolean[c];
        this.admittedInCurrentTimestep = 0;
        this.completedEvictionsInCurrentTimestep = 0;
        this.round1AdmissionOpen = true;
        this.timestepAdmissionClosedNs = -1L;
        this.firstEvictionCompletedNs = -1L;

        logger.info(
                "ORAM server ready, totalClients={}, c={}, historyVersions={}, retainAllHistory={}, treeHeight={}, rootBucketSize={}, competitionBucketSize={}, bucketSize={}, blockSize={}, bidSpace={}",
                totalClients, c, historyVersions, oramState.isRetainAllHistory(), treeHeight, rootBucketSize, competitionBucketSize, bucketSize, blockSize, bidSpace
        );
        logger.info("Server total storage[bytes]: {}", oramState.getServerTotalStorageBytes());
    }

    public static void main(String[] args) throws InterruptedException {
        if (args.length != 9) {
            System.out.println("Usage: oram.server.ORAMServer " +
                    "<totalClients> <c> <bidExponent> <rootBucketSize> <competitionBucketSize> <bucketSize> <blockSize> <ip> <port>");
            System.exit(-1);
        }

        int totalClients = Integer.parseInt(args[0]);
        int c = Integer.parseInt(args[1]);
        int bidExponent = Integer.parseInt(args[2]);
        int rootBucketSize = Integer.parseInt(args[3]);
        int competitionBucketSize = Integer.parseInt(args[4]);
        int bucketSize = Integer.parseInt(args[5]);
        int blockSize = Integer.parseInt(args[6]);
        String ip = args[7];
        int port = Integer.parseInt(args[8]);

        new ORAMServer(totalClients, c, bidExponent, rootBucketSize, competitionBucketSize, bucketSize, blockSize, ip, port);
    }

    @Override
    public byte[] execute(int sender, byte[] data) {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("Empty request");
        }

        ServerOperationType operation = ServerOperationType.getOperation(data[0]);

        switch (operation) {
            case INIT_GBMP:
                return handleInitGbMp(sender, data);
            case ROUND1_QUERY:
                enqueueRound1Query(sender, data);
                return null;
            case ROUND2_READ_PATH:
                return handleRound2ReadPath(sender, data);
            case ROUND2_READ_PATH_PAIR:
                return handleRound2ReadPathPair(sender, data);
            case ROUND3_UPDATE_CUR:
                return handleRound3UpdateCur(sender, data);
            case ROUND3_SUBMIT_EVICTION:
                return handleRound3SubmitEviction(sender, data);
            default:
                throw new IllegalArgumentException("Unsupported operation in current build: " + operation);
        }
    }
    
    private byte[] handleInitGbMp(int sender, byte[] data) {
        InitGbMpRequest request = new InitGbMpRequest();
        request.readExternal(data, 1);

        InitGbMpResponse response = new InitGbMpResponse(oramState.getEncryptedGbMp());
        byte[] serializedResponse = new byte[response.getSerializedSize()];
        response.writeExternal(serializedResponse, 0);
        return serializedResponse;
    }

    private void enqueueRound1Query(int sender, byte[] data) {
        Round1QueryRequest request = new Round1QueryRequest();
        request.readExternal(data, 1);

        synchronized (round1Lock) {
            pendingRound1Requests.addLast(new PendingRound1Request(sender, request, System.nanoTime()));
            maxPendingRound1Requests = Math.max(maxPendingRound1Requests, pendingRound1Requests.size());
            drainPendingRound1Requests();
        }
    }

    private void drainPendingRound1Requests() {
        while (round1AdmissionOpen
                && admittedInCurrentTimestep < c
                && !pendingRound1Requests.isEmpty()) {
            PendingRound1Request pending = pendingRound1Requests.removeFirst();
            long buildStartNs = System.nanoTime();
            long queueWaitNs = buildStartNs - pending.enqueuedNs;
            Round1BuildResult result = buildRound1Response(pending.request);
            long buildEndNs = System.nanoTime();
            byte[] response = result.response;
            admittedRound1Count++;
            totalRound1QueueWaitNs += queueWaitNs;
            maxRound1QueueWaitNs = Math.max(maxRound1QueueWaitNs, queueWaitNs);
            totalRound1BuildNs += buildEndNs - buildStartNs;
            totalRound1ResponseBytes += response.length;
            totalReturnedUdMpVersions += result.udMpVersionCount;
            maxReturnedUdMpVersions = Math.max(maxReturnedUdMpVersions, result.udMpVersionCount);
            admittedInCurrentTimestep++;
            if (admittedInCurrentTimestep == c) {
                round1AdmissionOpen = false;
                timestepAdmissionClosedNs = System.nanoTime();
                firstEvictionCompletedNs = -1L;
            }
            sendResponse(pending.sender, response);
        }
    }

    private Round1BuildResult buildRound1Response(Round1QueryRequest request) {
        int seq;
        int timestep;
        int slot;
        List<byte[]> priorEncryptedRequests;

        seq = nextSeq++;
        timestep = seq / c;
        slot = seq % c;

        if (timestep != currentTimestep) {
            currentTimestep = timestep;
            currentEncryptedQL.clear();
        }

        priorEncryptedRequests = copyEncryptedRequestList(currentEncryptedQL);
        currentEncryptedQL.add(copyBytes(request.getEncryptedRequestItem()));

        List<byte[][]> udMpHistory = oramState.getEncryptedUdMpHistoryAfter(
                request.getLastAppliedTimestep(),
                timestep
        );
        Round1QueryResponse response = new Round1QueryResponse(
                seq,
                timestep,
                slot,
                priorEncryptedRequests,
                new java.util.HashMap<>(),
                udMpHistory,
                oramState.getLatestUdMpTimestepBefore(timestep),
                oramState.getEncryptedLastRL(),
                oramState.getEncryptedRLForTimestep(request.getDelayedTimestep())
        );

        byte[] serializedResponse = new byte[response.getSerializedSize()];
        response.writeExternal(serializedResponse, 0);
        return new Round1BuildResult(serializedResponse, udMpHistory.size());
    }

    private byte[] handleRound2ReadPath(int sender, byte[] data) {
        Round2ReadPathRequest request = new Round2ReadPathRequest();
        request.readExternal(data, 1);

        int pid = request.getPid();
        EncryptedORAMPath encryptedPath = oramState.readEncryptedPath(pid);
        
        Round2ReadPathResponse response = new Round2ReadPathResponse(encryptedPath);
        byte[] serializedResponse = new byte[response.getSerializedSize()];
        response.writeExternal(serializedResponse, 0);
        return serializedResponse;
    }

    private byte[] handleRound2ReadPathPair(int sender, byte[] data) {
        Round2ReadPathPairRequest request = new Round2ReadPathPairRequest();
        request.readExternal(data, 1);

        int firstPid = request.getFirstPid();
        int secondPid = request.getSecondPid();
        EncryptedORAMPath firstPath = oramState.readEncryptedPath(firstPid);
        int sharedPrefixLength = oramState.sharedPrefixLength(firstPid, secondPid);
        EncryptedORAMBucket[] secondSuffix = oramState.readEncryptedPathSuffix(secondPid, sharedPrefixLength);

        Round2ReadPathPairResponse response = new Round2ReadPathPairResponse(
                firstPath,
                secondPid,
                sharedPrefixLength,
                secondSuffix
        );
        byte[] serializedResponse = new byte[response.getSerializedSize()];
        response.writeExternal(serializedResponse, 0);
        return serializedResponse;
    }

    private byte[] handleRound3UpdateCur(int sender, byte[] data) {
        Round3UpdateCurRequest request = new Round3UpdateCurRequest();
        request.readExternal(data, 1);

        boolean timestepAdvanced = oramState.updateCur(
                request.getSeq(),
                request.getEncryptedSlotCur(),
                request.getEncryptedMpCur()
        );

        Round3UpdateCurResponse response = new Round3UpdateCurResponse(timestepAdvanced);
        byte[] serializedResponse = new byte[response.getSerializedSize()];
        response.writeExternal(serializedResponse, 0);
        return serializedResponse;
    }

    private byte[] handleRound3SubmitEviction(int sender, byte[] data) {
        Round3SubmitEvictionRequest request = new Round3SubmitEvictionRequest();
        request.readExternal(data, 1);

        oramState.submitEviction(
                request.getSeq(),
                request.getPid(),
                request.getEncryptedSegments()
        );

        synchronized (round1Lock) {
            markEvictionComplete(request.getSeq());
        }
        
        Round3SubmitEvictionResponse response = new Round3SubmitEvictionResponse(true);
        byte[] serializedResponse = new byte[response.getSerializedSize()];
        response.writeExternal(serializedResponse, 0);

        return serializedResponse;
    }

    private void markEvictionComplete(int seq) {
        int timestep = seq / c;
        int slot = seq % c;
        if (timestep != currentTimestep) {
            throw new IllegalStateException(
                    "Eviction completed for timestep " + timestep
                            + " while current timestep is " + currentTimestep
            );
        }
        if (completedEvictionSlots[slot]) {
            throw new IllegalStateException(
                    "Duplicate eviction completion for timestep " + timestep + ", slot " + slot
            );
        }

        completedEvictionSlots[slot] = true;
        completedEvictionsInCurrentTimestep++;
        completedEvictionCount++;
        long nowNs = System.nanoTime();
        if (firstEvictionCompletedNs < 0L) {
            firstEvictionCompletedNs = nowNs;
        }
        if (completedEvictionsInCurrentTimestep != c) {
            return;
        }

        long evictionTailNs = nowNs - firstEvictionCompletedNs;
        totalEvictionTailNs += evictionTailNs;
        maxEvictionTailNs = Math.max(maxEvictionTailNs, evictionTailNs);
        if (timestepAdmissionClosedNs >= 0L) {
            totalTimestepActiveNs += nowNs - timestepAdmissionClosedNs;
        }
        completedTimestepCount++;

        for (int i = 0; i < c; i++) {
            completedEvictionSlots[i] = false;
        }
        completedEvictionsInCurrentTimestep = 0;
        admittedInCurrentTimestep = 0;
        round1AdmissionOpen = true;
        if (completedEvictionCount % 1000L == 0L) {
            logDiagnostics();
        }
        drainPendingRound1Requests();
    }

    private void logDiagnostics() {
        double admitted = Math.max(1L, admittedRound1Count);
        double timesteps = Math.max(1L, completedTimestepCount);
        logger.info(
                "server diagnostic summary admitted={} completed={} timesteps={} avgRound1QueueWaitMs={} " +
                        "maxRound1QueueWaitMs={} avgRound1BuildMs={} avgRound1ResponseBytes={} " +
                        "avgReturnedUdMpVersions={} maxReturnedUdMpVersions={} maxPendingRound1Requests={} " +
                        "avgTimestepActiveMs={} avgEvictionTailMs={} maxEvictionTailMs={} " +
                        "retainedUdMpVersions={} retainedRlVersions={}",
                admittedRound1Count,
                completedEvictionCount,
                completedTimestepCount,
                totalRound1QueueWaitNs / admitted / 1_000_000.0,
                maxRound1QueueWaitNs / 1_000_000.0,
                totalRound1BuildNs / admitted / 1_000_000.0,
                totalRound1ResponseBytes / admitted,
                totalReturnedUdMpVersions / admitted,
                maxReturnedUdMpVersions,
                maxPendingRound1Requests,
                totalTimestepActiveNs / timesteps / 1_000_000.0,
                totalEvictionTailNs / timesteps / 1_000_000.0,
                maxEvictionTailNs / 1_000_000.0,
                oramState.getUdMpHistorySize(),
                oramState.getRlHistorySize()
        );
    }

    private static final class PendingRound1Request {
        private final int sender;
        private final Round1QueryRequest request;
        private final long enqueuedNs;

        private PendingRound1Request(int sender, Round1QueryRequest request, long enqueuedNs) {
            this.sender = sender;
            this.request = request;
            this.enqueuedNs = enqueuedNs;
        }
    }

    private static final class Round1BuildResult {
        private final byte[] response;
        private final int udMpVersionCount;

        private Round1BuildResult(byte[] response, int udMpVersionCount) {
            this.response = response;
            this.udMpVersionCount = udMpVersionCount;
        }
    }

    private List<byte[]> copyEncryptedRequestList(List<byte[]> source) {
        List<byte[]> copy = new ArrayList<>(source.size());
        for (byte[] item : source) {
            copy.add(copyBytes(item));
        }
        return copy;
    }

    private byte[] copyBytes(byte[] source) {
        byte[] copy = new byte[source.length];
        System.arraycopy(source, 0, copy, 0, source.length);
        return copy;
    }

    public void shutdown() {
        serverCommunicationSystem.shutdown();
        interrupt();
    }
}
