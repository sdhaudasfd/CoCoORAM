package oram.client;

import comunication.Message;
import oram.messages.InitGbMpRequest;
import oram.messages.InitGbMpResponse;
import oram.messages.Round1QueryRequest;
import oram.messages.Round1QueryResponse;
import oram.messages.Round3UpdateCurRequest;
import oram.messages.Round3UpdateCurResponse;
import oram.structure.GlobalMapEntry;
import oram.utils.ORAMUtils;
import oram.utils.Operation;
import oram.utils.ServerOperationType;
import oram.messages.Round2ReadPathRequest;
import oram.messages.Round2ReadPathResponse;
import oram.security.ProtocolEncryptionManager;
import oram.structure.EncryptedGlobalMapEntry;
import oram.structure.ORAMPath;
import oram.messages.Round3SubmitEvictionRequest;
import oram.messages.Round3SubmitEvictionResponse;
import oram.structure.EncryptedORAMPath;

import java.util.HashMap;
import java.util.Map;

public class ORAMManager {
    private static final int MESSAGE_TYPE = 1;

    private final ServiceProxy serviceProxy;
    private final Round1RequestItemCrypto round1Crypto;
    private final ProtocolEncryptionManager encryptionManager;

    public ORAMManager(int clientId, String serverIP, int serverPort) {
        this.serviceProxy = new ServiceProxy(clientId, MESSAGE_TYPE, serverIP, serverPort);
        this.round1Crypto = new Round1RequestItemCrypto();
        this.encryptionManager = new ProtocolEncryptionManager();
    }

    public Map<Integer, GlobalMapEntry> initializeGbMp() {
        InitGbMpRequest request = new InitGbMpRequest();
        byte[] serializedRequest = ORAMUtils.serializeRequest(ServerOperationType.INIT_GBMP, request);

        Message responseMessage = serviceProxy.sendMessage(serializedRequest);
        if (responseMessage == null || responseMessage.getSerializedMessage() == null) {
            throw new IllegalStateException("No InitGbMp response from server");
        }

        InitGbMpResponse response = new InitGbMpResponse();
        response.readExternal(responseMessage.getSerializedMessage(), 0);

        Map<Integer, GlobalMapEntry> decoded = new HashMap<>(response.getEncryptedGbMp().size());
        for (Map.Entry<Integer, oram.structure.EncryptedGlobalMapEntry> entry : response.getEncryptedGbMp().entrySet()) {
            decoded.put(entry.getKey(), encryptionManager.decryptGlobalMapEntry(entry.getValue()));
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

        Round3UpdateCurResponse response = new Round3UpdateCurResponse();
        response.readExternal(responseMessage.getSerializedMessage(), 0);
        return response;
    }

    public Round3SubmitEvictionResponse submitRound3Eviction(EncryptedORAMPath encryptedPathPrime,
                                                             int[] ownedBucketIndexes,
                                                             int bidStartInclusive,
                                                             EncryptedGlobalMapEntry[] encryptedMpPrime) {
        Round3SubmitEvictionRequest request = new Round3SubmitEvictionRequest(
                encryptedPathPrime,
                ownedBucketIndexes,
                bidStartInclusive,
                encryptedMpPrime
        );
        byte[] serializedRequest = ORAMUtils.serializeRequest(
                ServerOperationType.ROUND3_SUBMIT_EVICTION,
                request
        );

        Message responseMessage = serviceProxy.sendMessage(serializedRequest);
        if (responseMessage == null || responseMessage.getSerializedMessage() == null) {
            throw new IllegalStateException("No Round3SubmitEviction response from server");
        }

        Round3SubmitEvictionResponse response = new Round3SubmitEvictionResponse();
        response.readExternal(responseMessage.getSerializedMessage(), 0);
        return response;
    }

    public void close() {
        serviceProxy.close();
    }
}
