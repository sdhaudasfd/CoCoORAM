package oram.client;

import confidential.client.ConfidentialServiceProxy;
import confidential.client.Response;
import comunication.Message;
import oram.messages.Round3CommitEvictionRequest;
import oram.messages.InitGbMpRequest;
import oram.messages.InitGbMpResponse;
import oram.messages.Round1QueryRequest;
import oram.messages.Round1QueryResponse;
import oram.client.module.Round1RequestItem;
import oram.structure.GlobalMapEntry;
import oram.utils.ORAMUtils;
import oram.utils.Operation;
import oram.utils.ServerOperationType;
import vss.facade.SecretSharingException;
import oram.messages.Round2ReadPathRequest;
import oram.messages.Round2ReadPathPairRequest;
import oram.messages.Round2ReadPathPairResponse;
import oram.messages.Round2ReadPathResponse;
import oram.security.ProtocolEncryptionManager;
import oram.structure.EncryptedORAMBucket;
import oram.structure.EncryptedORAMPath;
import oram.structure.ORAMPath;
import oram.messages.Round3SubmitEvictionRequest;
import oram.messages.Round3SubmitEvictionResponse;
import oram.structure.EncryptedBucketSegment;

import java.util.concurrent.atomic.AtomicLong;

public class ORAMManager {
    private static final int MESSAGE_TYPE = 1;
    private static final AtomicLong round1RequestBytes = new AtomicLong();
    private static final AtomicLong round1ResponseBytes = new AtomicLong();
    private static final AtomicLong round2RequestBytes = new AtomicLong();
    private static final AtomicLong round2ResponseBytes = new AtomicLong();
    private static final AtomicLong round3RequestBytes = new AtomicLong();
    private static final AtomicLong round3ResponseBytes = new AtomicLong();

    private final int clientId;
    private final ServiceProxy serviceProxy;
    private final ConfidentialServiceProxy bftServiceProxy;
    private final boolean bftMode;
    private final Round1RequestItemCrypto round1Crypto;
    private final ProtocolEncryptionManager encryptionManager;

    public ORAMManager(int clientId, String serverIP, int serverPort) {
        this.clientId = clientId;
        this.serviceProxy = new ServiceProxy(clientId, MESSAGE_TYPE, serverIP, serverPort);
        this.bftServiceProxy = null;
        this.bftMode = false;
        this.round1Crypto = new Round1RequestItemCrypto();
        this.encryptionManager = new ProtocolEncryptionManager();
    }

    public ORAMManager(int clientId) {
        this.clientId = clientId;
        try {
            this.bftServiceProxy = new ConfidentialServiceProxy(clientId);
        } catch (SecretSharingException e) {
            throw new RuntimeException("Failed to connect to BFT ORAM replicas", e);
        }
        this.serviceProxy = null;
        this.bftMode = true;
        this.round1Crypto = new Round1RequestItemCrypto();
        this.encryptionManager = new ProtocolEncryptionManager();
    }

    public GlobalMapEntry[] initializeGbMp(int bidSpace) {
        InitGbMpRequest request = new InitGbMpRequest();
        byte[] serializedRequest = ORAMUtils.serializeRequest(ServerOperationType.INIT_GBMP, request);

        byte[] responseBytes = sendOrderedHashed(serializedRequest);
        if (responseBytes == null) {
            throw new IllegalStateException("No InitGbMp response from server");
        }

        InitGbMpResponse response = new InitGbMpResponse();
        response.readExternal(responseBytes, 0);

        GlobalMapEntry[] decoded = new GlobalMapEntry[bidSpace];
        oram.structure.EncryptedGlobalMapEntry[] encryptedGbMp = response.getEncryptedGbMp();
        int length = Math.min(decoded.length, encryptedGbMp.length);
        for (int bid = 0; bid < length; bid++) {
            if (encryptedGbMp[bid] != null) {
                decoded[bid] = encryptionManager.decryptGlobalMapEntry(encryptedGbMp[bid]);
            }
        }
        return decoded;
    }

    public Round1QueryResponse submitRound1Query(Operation operation, int bid) {
        Round1RequestItem item = new Round1RequestItem(operation, bid);
        byte[] encryptedRequestItem = round1Crypto.encrypt(item);

        Round1QueryRequest request = new Round1QueryRequest(encryptedRequestItem);
        byte[] serializedRequest = ORAMUtils.serializeRequest(ServerOperationType.ROUND1_QUERY, request);

        byte[] responseBytes = sendOrderedHashed(serializedRequest);
        if (responseBytes == null) {
            throw new IllegalStateException("No Round1Query response from server");
        }
        round1RequestBytes.addAndGet(serializedRequest.length);
        round1ResponseBytes.addAndGet(responseBytes.length);

        Round1QueryResponse response = new Round1QueryResponse();
        response.readExternal(responseBytes, 0);
        return response;
    }

    public ORAMPath readRound2Path(int pid) {
        Round2ReadPathRequest request = new Round2ReadPathRequest(pid);
        byte[] serializedRequest = ORAMUtils.serializeRequest(ServerOperationType.ROUND2_READ_PATH, request);

        byte[] responseBytes = sendUnorderedHashed(serializedRequest);
        if (responseBytes == null) {
            throw new IllegalStateException("No Round2ReadPath response from server");
        }
        round2RequestBytes.addAndGet(serializedRequest.length);
        round2ResponseBytes.addAndGet(responseBytes.length);

        Round2ReadPathResponse response = new Round2ReadPathResponse();
        response.readExternal(responseBytes, 0);

        return encryptionManager.decryptPath(response.getEncryptedPath());
    }

    public Round2PathPair readRound2PathPair(int firstPid, int secondPid) {
        Round2ReadPathPairRequest request = new Round2ReadPathPairRequest(firstPid, secondPid);
        byte[] serializedRequest = ORAMUtils.serializeRequest(ServerOperationType.ROUND2_READ_PATH_PAIR, request);

        byte[] responseBytes = sendUnorderedHashed(serializedRequest);
        if (responseBytes == null) {
            throw new IllegalStateException("No Round2ReadPathPair response from server");
        }
        round2RequestBytes.addAndGet(serializedRequest.length);
        round2ResponseBytes.addAndGet(responseBytes.length);

        Round2ReadPathPairResponse response = new Round2ReadPathPairResponse();
        response.readExternal(responseBytes, 0);

        EncryptedORAMPath firstEncryptedPath = response.getFirstPath();
        EncryptedORAMBucket[] firstBuckets = firstEncryptedPath.getBuckets();
        int sharedPrefixLength = response.getSharedPrefixLength();
        EncryptedORAMBucket[] secondSuffix = response.getSecondSuffixBuckets();
        if (sharedPrefixLength < 0 || sharedPrefixLength > firstBuckets.length ||
                sharedPrefixLength + secondSuffix.length != firstBuckets.length) {
            throw new IllegalStateException(
                    "Invalid Round2 path pair response, sharedPrefixLength=" + sharedPrefixLength +
                            ", firstLength=" + firstBuckets.length +
                            ", suffixLength=" + secondSuffix.length
            );
        }

        EncryptedORAMBucket[] secondBuckets = new EncryptedORAMBucket[firstBuckets.length];
        for (int level = 0; level < sharedPrefixLength; level++) {
            secondBuckets[level] = firstBuckets[level];
        }
        for (int i = 0; i < secondSuffix.length; i++) {
            secondBuckets[sharedPrefixLength + i] = secondSuffix[i];
        }

        ORAMPath firstPath = encryptionManager.decryptPath(firstEncryptedPath);
        ORAMPath secondPath = encryptionManager.decryptPath(
                new EncryptedORAMPath(response.getSecondPid(), secondBuckets)
        );
        return new Round2PathPair(firstPath, secondPath);
    }

    public Round3SubmitEvictionResponse submitRound3Eviction(int seq,
                                                             byte[] encryptedSlotCur,
                                                             byte[] encryptedMpCur,
                                                             int pid,
                                                             EncryptedBucketSegment[] encryptedSegments) {
        Round3SubmitEvictionRequest payloadRequest = new Round3SubmitEvictionRequest(
                seq,
                encryptedSlotCur,
                encryptedMpCur,
                pid,
                encryptedSegments
        );
        byte[] serializedPayloadRequest = ORAMUtils.serializeRequest(
                ServerOperationType.ROUND3_SUBMIT_EVICTION,
                payloadRequest
        );

        byte[] payloadResponseBytes = sendUnordered(serializedPayloadRequest);
        if (payloadResponseBytes == null) {
            throw new IllegalStateException("No Round3SubmitEviction payload response from server");
        }

        int payloadKey = clientId + ORAMUtils.computeHashCode(serializedPayloadRequest) * 32;
        Round3CommitEvictionRequest commitRequest = new Round3CommitEvictionRequest(payloadKey);
        byte[] serializedCommitRequest = ORAMUtils.serializeRequest(
                ServerOperationType.ROUND3_COMMIT_EVICTION,
                commitRequest
        );

        byte[] responseBytes = sendOrdered(serializedCommitRequest);
        if (responseBytes == null) {
            throw new IllegalStateException("No Round3CommitEviction response from server");
        }
        round3RequestBytes.addAndGet(serializedPayloadRequest.length + serializedCommitRequest.length);
        round3ResponseBytes.addAndGet(payloadResponseBytes.length + responseBytes.length);

        Round3SubmitEvictionResponse response = new Round3SubmitEvictionResponse();
        response.readExternal(responseBytes, 0);

        return response;
    }

    public void close() {
        if (bftMode) {
            bftServiceProxy.close();
        } else {
            serviceProxy.close();
        }
    }

    private byte[] sendOrdered(byte[] request) {
        if (!bftMode) {
            return sendSingle(request);
        }
        try {
            Response response = bftServiceProxy.invokeOrdered(request);
            return response == null ? null : response.getPlainData();
        } catch (SecretSharingException e) {
            throw new RuntimeException("BFT ordered request failed", e);
        }
    }

    private byte[] sendOrderedHashed(byte[] request) {
        if (!bftMode) {
            return sendSingle(request);
        }
        try {
            Response response = bftServiceProxy.invokeOrderedHashed(request);
            return response == null ? null : response.getPlainData();
        } catch (SecretSharingException e) {
            throw new RuntimeException("BFT ordered hashed request failed", e);
        }
    }

    private byte[] sendUnordered(byte[] request) {
        if (!bftMode) {
            return sendSingle(request);
        }
        try {
            Response response = bftServiceProxy.invokeUnordered(request);
            return response == null ? null : response.getPlainData();
        } catch (SecretSharingException e) {
            throw new RuntimeException("BFT unordered request failed", e);
        }
    }

    private byte[] sendUnorderedHashed(byte[] request) {
        if (!bftMode) {
            return sendSingle(request);
        }
        try {
            Response response = bftServiceProxy.invokeUnorderedHashed(request);
            return response == null ? null : response.getPlainData();
        } catch (SecretSharingException e) {
            throw new RuntimeException("BFT unordered hashed request failed", e);
        }
    }

    private byte[] sendSingle(byte[] request) {
        Message responseMessage = serviceProxy.sendMessage(request);
        return responseMessage == null ? null : responseMessage.getSerializedMessage();
    }

    public static void resetBandwidthStats() {
        round1RequestBytes.set(0L);
        round1ResponseBytes.set(0L);
        round2RequestBytes.set(0L);
        round2ResponseBytes.set(0L);
        round3RequestBytes.set(0L);
        round3ResponseBytes.set(0L);
    }

    public static long getRound1TotalBytes() {
        return round1RequestBytes.get() + round1ResponseBytes.get();
    }

    public static long getRound2TotalBytes() {
        return round2RequestBytes.get() + round2ResponseBytes.get();
    }

    public static long getRound3TotalBytes() {
        return round3RequestBytes.get() + round3ResponseBytes.get();
    }

    public static long getTotalProtocolBytes() {
        return getRound1TotalBytes() + getRound2TotalBytes() + getRound3TotalBytes();
    }

    public static final class Round2PathPair {
        private final ORAMPath firstPath;
        private final ORAMPath secondPath;

        private Round2PathPair(ORAMPath firstPath, ORAMPath secondPath) {
            this.firstPath = firstPath;
            this.secondPath = secondPath;
        }

        public ORAMPath getFirstPath() {
            return firstPath;
        }

        public ORAMPath getSecondPath() {
            return secondPath;
        }
    }
}
