package oram.client;

import comunication.Message;
import oram.messages.InitGbMpRequest;
import oram.messages.InitGbMpResponse;
import oram.messages.Round1QueryRequest;
import oram.messages.Round1QueryResponse;
import oram.client.module.Round1RequestItem;
import oram.messages.Round3UpdateCurRequest;
import oram.messages.Round3UpdateCurResponse;
import oram.structure.GlobalMapEntry;
import oram.utils.ORAMUtils;
import oram.utils.Operation;
import oram.utils.ServerOperationType;
import oram.messages.Round2ReadPathRequest;
import oram.messages.Round2ReadPathResponse;
import oram.security.ProtocolEncryptionManager;
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

    private final ServiceProxy serviceProxy;
    private final Round1RequestItemCrypto round1Crypto;
    private final ProtocolEncryptionManager encryptionManager;

    public ORAMManager(int clientId, String serverIP, int serverPort) {
        this.serviceProxy = new ServiceProxy(clientId, MESSAGE_TYPE, serverIP, serverPort);
        this.round1Crypto = new Round1RequestItemCrypto();
        this.encryptionManager = new ProtocolEncryptionManager();
    }

    public GlobalMapEntry[] initializeGbMp(int bidSpace) {
        InitGbMpRequest request = new InitGbMpRequest();
        byte[] serializedRequest = ORAMUtils.serializeRequest(ServerOperationType.INIT_GBMP, request);

        Message responseMessage = serviceProxy.sendMessage(serializedRequest);
        if (responseMessage == null || responseMessage.getSerializedMessage() == null) {
            throw new IllegalStateException("No InitGbMp response from server");
        }

        InitGbMpResponse response = new InitGbMpResponse();
        response.readExternal(responseMessage.getSerializedMessage(), 0);

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

        Message responseMessage = serviceProxy.sendMessage(serializedRequest);
        if (responseMessage == null || responseMessage.getSerializedMessage() == null) {
            throw new IllegalStateException("No Round1Query response from server");
        }
        round1RequestBytes.addAndGet(serializedRequest.length);
        round1ResponseBytes.addAndGet(responseMessage.getSerializedMessage().length);

        Round1QueryResponse response = new Round1QueryResponse();
        response.readExternal(responseMessage.getSerializedMessage(), 0);
        return response;
    }

    public ORAMPath readRound2Path(int pid) {
        Round2ReadPathRequest request = new Round2ReadPathRequest(pid);
        byte[] serializedRequest = ORAMUtils.serializeRequest(ServerOperationType.ROUND2_READ_PATH, request);

        Message responseMessage = serviceProxy.sendMessage(serializedRequest);
        if (responseMessage == null || responseMessage.getSerializedMessage() == null) {
            throw new IllegalStateException("No Round2ReadPath response from server");
        }
        round2RequestBytes.addAndGet(serializedRequest.length);
        round2ResponseBytes.addAndGet(responseMessage.getSerializedMessage().length);

        Round2ReadPathResponse response = new Round2ReadPathResponse();
        response.readExternal(responseMessage.getSerializedMessage(), 0);

        return encryptionManager.decryptPath(response.getEncryptedPath());
    }

    public Round3UpdateCurResponse submitRound3UpdateCur(int seq, byte[] encryptedSlotCur, byte[] encryptedMpCur) {
        Round3UpdateCurRequest request = new Round3UpdateCurRequest(seq, encryptedSlotCur, encryptedMpCur);
        byte[] serializedRequest = ORAMUtils.serializeRequest(ServerOperationType.ROUND3_UPDATE_CUR, request);

        Message responseMessage = serviceProxy.sendMessage(serializedRequest);
        if (responseMessage == null || responseMessage.getSerializedMessage() == null) {
            throw new IllegalStateException("No Round3UpdateCur response from server");
        }
        round3RequestBytes.addAndGet(serializedRequest.length);
        round3ResponseBytes.addAndGet(responseMessage.getSerializedMessage().length);

        Round3UpdateCurResponse response = new Round3UpdateCurResponse();
        response.readExternal(responseMessage.getSerializedMessage(), 0);
        return response;
    }

    public Round3SubmitEvictionResponse submitRound3Eviction(int seq,
                                                             int pid,
                                                             EncryptedBucketSegment[] encryptedSegments) {
        Round3SubmitEvictionRequest request = new Round3SubmitEvictionRequest(
                seq,
                pid,
                encryptedSegments
        );
        byte[] serializedRequest = ORAMUtils.serializeRequest(
                ServerOperationType.ROUND3_SUBMIT_EVICTION,
                request
        );

        Message responseMessage = serviceProxy.sendMessage(serializedRequest);
        if (responseMessage == null || responseMessage.getSerializedMessage() == null) {
            throw new IllegalStateException("No Round3SubmitEviction response from server");
        }
        round3RequestBytes.addAndGet(serializedRequest.length);
        round3ResponseBytes.addAndGet(responseMessage.getSerializedMessage().length);

        Round3SubmitEvictionResponse response = new Round3SubmitEvictionResponse();
        response.readExternal(responseMessage.getSerializedMessage(), 0);

        return response;
    }

    public void close() {
        serviceProxy.close();
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
}
