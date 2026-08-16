package oram.server;

import oram.messages.InitGbMpRequest;
import oram.messages.InitGbMpResponse;
import oram.messages.Round1QueryRequest;
import oram.messages.Round1QueryResponse;
import oram.messages.Round2ReadPathPairRequest;
import oram.messages.Round2ReadPathPairResponse;
import oram.messages.Round2ReadPathRequest;
import oram.messages.Round2ReadPathResponse;
import oram.messages.Round3CommitEvictionRequest;
import oram.messages.Round3SubmitEvictionRequest;
import oram.messages.Round3SubmitEvictionResponse;
import oram.messages.Round3UpdateCurRequest;
import oram.structure.EncryptedORAMBucket;
import oram.structure.EncryptedORAMPath;
import oram.utils.ORAMUtils;
import oram.utils.ServerOperationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ORAMService {
    private static final Logger logger = LoggerFactory.getLogger("oram");

    private final int c;
    private final ORAMState oramState;
    private int nextSeq;
    private int currentTimestep;
    private final List<byte[]> currentEncryptedQL;
    private final Object round1Lock = new Object();
    private final Map<Integer, Round3SubmitEvictionRequest> pendingEvictionPayloads;

    public ORAMService(int c,
                       int bidExponent,
                       int rootBucketSize,
                       int competitionBucketSize,
                       int bucketSize,
                       int blockSize) {
        if (c <= 0) {
            throw new IllegalArgumentException("c must be positive");
        }
        if (bidExponent <= 0 || bidExponent >= 31) {
            throw new IllegalArgumentException("bidExponent must be in [1, 30]");
        }

        int treeHeight = bidExponent + 1;
        int bidSpace = 1 << bidExponent;

        this.c = c;
        this.oramState = new ORAMState(c, treeHeight, rootBucketSize, competitionBucketSize, bucketSize, blockSize, bidSpace);
        this.nextSeq = 0;
        this.currentTimestep = 0;
        this.currentEncryptedQL = new ArrayList<>();
        this.pendingEvictionPayloads = new HashMap<>();

        logger.info(
                "ORAM service ready, c={}, treeHeight={}, rootBucketSize={}, competitionBucketSize={}, bucketSize={}, blockSize={}, bidSpace={}",
                c, treeHeight, rootBucketSize, competitionBucketSize, bucketSize, blockSize, bidSpace
        );
        logger.info("Server total storage[bytes]: {}", oramState.getServerTotalStorageBytes());
    }

    public byte[] executeOrdered(int sender, byte[] data) {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("Empty request");
        }

        ServerOperationType operation = ServerOperationType.getOperation(data[0]);

        switch (operation) {
            case INIT_GBMP:
                return handleInitGbMp(data);
            case ROUND1_QUERY:
                return handleRound1Query(data);
            case ROUND2_READ_PATH:
                return handleRound2ReadPath(data);
            case ROUND2_READ_PATH_PAIR:
                return handleRound2ReadPathPair(data);
            case ROUND3_UPDATE_CUR:
                return handleRound3UpdateCur(data);
            case ROUND3_SUBMIT_EVICTION:
                return handleRound3SubmitEvictionPayload(sender, data);
            case ROUND3_COMMIT_EVICTION:
                return handleRound3CommitEviction(data);
            default:
                throw new IllegalArgumentException("Unsupported ordered operation: " + operation);
        }
    }

    public byte[] executeUnordered(int sender, byte[] data) {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("Empty request");
        }

        ServerOperationType operation = ServerOperationType.getOperation(data[0]);

        switch (operation) {
            case INIT_GBMP:
                return handleInitGbMp(data);
            case ROUND2_READ_PATH:
                return handleRound2ReadPath(data);
            case ROUND2_READ_PATH_PAIR:
                return handleRound2ReadPathPair(data);
            case ROUND3_SUBMIT_EVICTION:
                return handleRound3SubmitEvictionPayload(sender, data);
            default:
                throw new IllegalArgumentException("Unsupported unordered operation: " + operation);
        }
    }

    private byte[] handleInitGbMp(byte[] data) {
        InitGbMpRequest request = new InitGbMpRequest();
        request.readExternal(data, 1);

        InitGbMpResponse response = new InitGbMpResponse(oramState.getEncryptedGbMp());
        return serialize(response);
    }

    private byte[] handleRound1Query(byte[] data) {
        Round1QueryRequest request = new Round1QueryRequest();
        request.readExternal(data, 1);

        int seq;
        int timestep;
        int slot;
        List<byte[]> priorEncryptedRequests;

        synchronized (round1Lock) {
            seq = nextSeq++;
            timestep = seq / c;
            slot = seq % c;

            if (timestep != currentTimestep) {
                currentTimestep = timestep;
                currentEncryptedQL.clear();
            }

            priorEncryptedRequests = copyEncryptedRequestList(currentEncryptedQL);
            currentEncryptedQL.add(copyBytes(request.getEncryptedRequestItem()));
        }

        Round1QueryResponse response = new Round1QueryResponse(
                seq,
                timestep,
                slot,
                priorEncryptedRequests,
                new java.util.HashMap<>(),
                oramState.getEncryptedLastUdMp(),
                oramState.getEncryptedLastRL()
        );

        return serialize(response);
    }

    private byte[] handleRound2ReadPath(byte[] data) {
        Round2ReadPathRequest request = new Round2ReadPathRequest();
        request.readExternal(data, 1);

        EncryptedORAMPath encryptedPath = oramState.readEncryptedPath(request.getPid());
        return serialize(new Round2ReadPathResponse(encryptedPath));
    }

    private byte[] handleRound2ReadPathPair(byte[] data) {
        Round2ReadPathPairRequest request = new Round2ReadPathPairRequest();
        request.readExternal(data, 1);

        int firstPid = request.getFirstPid();
        int secondPid = request.getSecondPid();
        EncryptedORAMPath firstPath = oramState.readEncryptedPath(firstPid);
        int sharedPrefixLength = oramState.sharedPrefixLength(firstPid, secondPid);
        EncryptedORAMBucket[] secondSuffix = oramState.readEncryptedPathSuffix(secondPid, sharedPrefixLength);

        return serialize(new Round2ReadPathPairResponse(
                firstPath,
                secondPid,
                sharedPrefixLength,
                secondSuffix
        ));
    }

    private byte[] handleRound3UpdateCur(byte[] data) {
        Round3UpdateCurRequest request = new Round3UpdateCurRequest();
        request.readExternal(data, 1);

        boolean timestepAdvanced = oramState.updateCur(
                request.getSeq(),
                request.getEncryptedSlotCur(),
                request.getEncryptedMpCur()
        );

        return serialize(new oram.messages.Round3UpdateCurResponse(timestepAdvanced));
    }

    private byte[] handleRound3SubmitEvictionPayload(int sender, byte[] data) {
        Round3SubmitEvictionRequest request = new Round3SubmitEvictionRequest();
        request.readExternal(data, 1);

        int payloadKey = payloadKey(sender, data);
        synchronized (pendingEvictionPayloads) {
            pendingEvictionPayloads.put(payloadKey, request);
            pendingEvictionPayloads.notifyAll();
        }

        return serialize(new Round3SubmitEvictionResponse(true));
    }

    private byte[] handleRound3CommitEviction(byte[] data) {
        Round3CommitEvictionRequest commit = new Round3CommitEvictionRequest();
        commit.readExternal(data, 1);

        Round3SubmitEvictionRequest request;
        synchronized (pendingEvictionPayloads) {
            while ((request = pendingEvictionPayloads.remove(commit.getPayloadKey())) == null) {
                try {
                    pendingEvictionPayloads.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting for round3 payload " + commit.getPayloadKey(), e);
                }
            }
        }

        oramState.updateCur(
                request.getSeq(),
                request.getEncryptedSlotCur(),
                request.getEncryptedMpCur()
        );
        oramState.submitEviction(
                request.getSeq(),
                request.getPid(),
                request.getEncryptedSegments()
        );

        return serialize(new Round3SubmitEvictionResponse(true));
    }

    private int payloadKey(int sender, byte[] serializedPayload) {
        return sender + ORAMUtils.computeHashCode(serializedPayload) * 32;
    }

    private byte[] serialize(oram.utils.RawCustomExternalizable response) {
        byte[] serializedResponse = new byte[response.getSerializedSize()];
        response.writeExternal(serializedResponse, 0);
        return serializedResponse;
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
}
