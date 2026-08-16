package oram.client;

import comunication.Message;
import oram.messages.DummyTurnRequest;
import oram.messages.InitPositionMapRequest;
import oram.messages.InitPositionMapResponse;
import oram.messages.ReadPathRequest;
import oram.messages.ReadPathResponse;
import oram.messages.RegisterAccessRequest;
import oram.messages.RegisterAccessResponse;
import oram.messages.SubmitTurnRequest;
import oram.messages.TurnUpdateRequest;
import oram.messages.TurnUpdateResponse;
import oram.security.ProtocolEncryptionManager;
import oram.structure.EncryptedGlobalMapEntry;
import oram.structure.EncryptedMapUpdate;
import oram.structure.EncryptedORAMPath;
import oram.structure.GlobalMapEntry;
import oram.structure.MapUpdate;
import oram.utils.ORAMUtils;
import oram.utils.ServerOperationType;

import java.util.concurrent.atomic.AtomicLong;

public class StrongMVPManager {
    private static final int MESSAGE_TYPE = 1;

    private final ServiceProxy serviceProxy;
    private final ProtocolEncryptionManager encryptionManager;
    private final AtomicLong sentBytes = new AtomicLong();
    private final AtomicLong receivedBytes = new AtomicLong();

    public StrongMVPManager(int clientId, String serverIp, int serverPort) {
        this.serviceProxy = new ServiceProxy(clientId, MESSAGE_TYPE, serverIp, serverPort);
        this.encryptionManager = new ProtocolEncryptionManager();
    }

    public GlobalMapEntry[] initializePositionMap(int bidSpace) {
        InitPositionMapRequest request = new InitPositionMapRequest();
        byte[] serialized = ORAMUtils.serializeRequest(ServerOperationType.INIT_POSITION_MAP, request);
        Message responseMessage = sendAndCount(serialized);
        if (responseMessage == null || responseMessage.getSerializedMessage() == null) {
            throw new IllegalStateException("No InitPositionMap response from server");
        }

        InitPositionMapResponse response = new InitPositionMapResponse();
        response.readExternal(responseMessage.getSerializedMessage(), 0);
        return decryptPositionMap(response.getEncryptedPositionMap(), bidSpace);
    }

    public int registerAccess() {
        RegisterAccessRequest request = new RegisterAccessRequest();
        byte[] serialized = ORAMUtils.serializeRequest(ServerOperationType.REGISTER_ACCESS, request);
        Message responseMessage = sendAndCount(serialized);
        if (responseMessage == null || responseMessage.getSerializedMessage() == null) {
            throw new IllegalStateException("No RegisterAccess response from server");
        }

        RegisterAccessResponse response = new RegisterAccessResponse();
        response.readExternal(responseMessage.getSerializedMessage(), 0);
        return response.getSeq();
    }

    public void acquireSearch() {
        sendSearchLeaseRequest(ServerOperationType.ACQUIRE_SEARCH);
    }

    public void releaseSearch() {
        sendSearchLeaseRequest(ServerOperationType.RELEASE_SEARCH);
    }

    private void sendSearchLeaseRequest(ServerOperationType operation) {
        RegisterAccessRequest request = new RegisterAccessRequest();
        byte[] serialized = ORAMUtils.serializeRequest(operation, request);
        Message responseMessage = sendAndCount(serialized);
        if (responseMessage == null || responseMessage.getSerializedMessage() == null) {
            throw new IllegalStateException("No " + operation + " response from server");
        }
        RegisterAccessResponse response = new RegisterAccessResponse();
        response.readExternal(responseMessage.getSerializedMessage(), 0);
    }

    public EncryptedORAMPath readPath(int pid) {
        ReadPathRequest request = new ReadPathRequest(pid);
        byte[] serialized = ORAMUtils.serializeRequest(ServerOperationType.READ_PATH, request);
        Message responseMessage = sendAndCount(serialized);
        if (responseMessage == null || responseMessage.getSerializedMessage() == null) {
            throw new IllegalStateException("No ReadPath response from server");
        }

        ReadPathResponse response = new ReadPathResponse();
        response.readExternal(responseMessage.getSerializedMessage(), 0);
        return response.getEncryptedPath();
    }

    public TurnUpdateResponse submitTurn(int seq,
                                         EncryptedORAMPath encryptedPath,
                                         EncryptedMapUpdate encryptedMapUpdate) {
        SubmitTurnRequest request = new SubmitTurnRequest(seq, encryptedPath, encryptedMapUpdate);
        byte[] serialized = ORAMUtils.serializeRequest(ServerOperationType.SUBMIT_TURN, request);
        Message responseMessage = sendAndCount(serialized);
        if (responseMessage == null || responseMessage.getSerializedMessage() == null) {
            throw new IllegalStateException("No SubmitTurn response from server");
        }

        TurnUpdateResponse response = new TurnUpdateResponse();
        response.readExternal(responseMessage.getSerializedMessage(), 0);
        return response;
    }

    public void submitDummyTurn(EncryptedORAMPath encryptedPath) {
        DummyTurnRequest request = new DummyTurnRequest(encryptedPath);
        byte[] serialized = ORAMUtils.serializeRequest(ServerOperationType.DUMMY_TURN, request);
        Message responseMessage = sendAndCount(serialized);
        if (responseMessage == null || responseMessage.getSerializedMessage() == null) {
            throw new IllegalStateException("No DummyTurn response from server");
        }
    }

    public TurnUpdateResponse waitTurnUpdate(int seq) {
        TurnUpdateRequest request = new TurnUpdateRequest(seq);
        byte[] serialized = ORAMUtils.serializeRequest(ServerOperationType.WAIT_TURN_UPDATE, request);
        Message responseMessage = sendAndCount(serialized);
        if (responseMessage == null || responseMessage.getSerializedMessage() == null) {
            throw new IllegalStateException("No TurnUpdate response from server");
        }

        TurnUpdateResponse response = new TurnUpdateResponse();
        response.readExternal(responseMessage.getSerializedMessage(), 0);
        return response;
    }

    public GlobalMapEntry[] decryptPositionMap(EncryptedGlobalMapEntry[] encryptedPositionMap, int bidSpace) {
        GlobalMapEntry[] positionMap = new GlobalMapEntry[bidSpace];
        int length = Math.min(bidSpace, encryptedPositionMap.length);
        for (int bid = 0; bid < length; bid++) {
            positionMap[bid] = encryptionManager.decryptGlobalMapEntry(encryptedPositionMap[bid]);
        }
        return positionMap;
    }

    public EncryptedMapUpdate encryptMapUpdate(MapUpdate update) {
        return encryptionManager.encryptMapUpdate(update);
    }

    public MapUpdate decryptMapUpdate(EncryptedMapUpdate update) {
        return encryptionManager.decryptMapUpdate(update);
    }

    public long getProtocolBytes() {
        return sentBytes.get() + receivedBytes.get();
    }

    private Message sendAndCount(byte[] serialized) {
        sentBytes.addAndGet(serialized.length);
        Message response = serviceProxy.sendMessage(serialized);
        if (response != null && response.getSerializedMessage() != null) {
            receivedBytes.addAndGet(response.getSerializedMessage().length);
        }
        return response;
    }

    public void close() {
        serviceProxy.close();
    }
}
