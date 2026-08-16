package oram.server;

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
import oram.structure.EncryptedORAMPath;
import oram.utils.ServerOperationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class StrongMVPServer extends ServerExecutable {
    private static final Logger logger = LoggerFactory.getLogger("oram");

    private final int c;
    private final StrongMVPState state;
    private final Object updateLock;
    private final int[] recentUpdateSeqs;
    private final byte[][] recentSerializedUpdates;
    private final int[] pendingWaiterSeqs;
    private final List<Integer>[] pendingUpdateWaiters;

    public StrongMVPServer(int c,
                           int bidExponent,
                           int rootBucketSize,
                           int bucketSize,
                           int blockSize,
                           String ip,
                           int port) throws InterruptedException {
        super(0, ip, port);
        if (c <= 0) {
            throw new IllegalArgumentException("c must be positive");
        }
        int treeHeight = bidExponent + 1;
        int bidSpace = 1 << bidExponent;
        this.c = c;
        this.state = new StrongMVPState(treeHeight, rootBucketSize, bucketSize, blockSize, bidSpace);
        this.updateLock = new Object();
        this.recentUpdateSeqs = new int[c];
        this.recentSerializedUpdates = new byte[c][];
        this.pendingWaiterSeqs = new int[c];
        this.pendingUpdateWaiters = createPendingWaiterLists(c);
        Arrays.fill(recentUpdateSeqs, -1);
        Arrays.fill(pendingWaiterSeqs, -1);
        logger.info(
                "Strong MVP server ready, c={}, treeHeight={}, rootBucketSize={}, bucketSize={}, blockSize={}, bidSpace={}",
                c, treeHeight, rootBucketSize, bucketSize, blockSize, bidSpace
        );
    }

    public static void main(String[] args) throws InterruptedException {
        if (args.length != 7) {
            System.out.println("Usage: oram.server.StrongMVPServer " +
                    "<c> <bidExponent> <rootBucketSize> <bucketSize> <blockSize> <ip> <port>");
            System.exit(-1);
        }

        int c = Integer.parseInt(args[0]);
        int bidExponent = Integer.parseInt(args[1]);
        int rootBucketSize = Integer.parseInt(args[2]);
        int bucketSize = Integer.parseInt(args[3]);
        int blockSize = Integer.parseInt(args[4]);
        String ip = args[5];
        int port = Integer.parseInt(args[6]);

        new StrongMVPServer(c, bidExponent, rootBucketSize, bucketSize, blockSize, ip, port);
    }

    @SuppressWarnings("unchecked")
    private static List<Integer>[] createPendingWaiterLists(int c) {
        List<Integer>[] waiters = new ArrayList[c];
        for (int i = 0; i < c; i++) {
            waiters[i] = new ArrayList<>();
        }
        return waiters;
    }

    @Override
    public byte[] execute(int sender, byte[] data) {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("Empty request");
        }

        ServerOperationType operation = ServerOperationType.getOperation(data[0]);
        switch (operation) {
            case INIT_POSITION_MAP:
                return handleInitPositionMap(data);
            case REGISTER_ACCESS:
                return handleRegisterAccess(data);
            case READ_PATH:
                return handleReadPath(data);
            case SUBMIT_TURN:
                return handleSubmitTurn(data);
            case DUMMY_TURN:
                return handleDummyTurn(data);
            case WAIT_TURN_UPDATE:
                return handleWaitTurnUpdate(sender, data);
            default:
                throw new IllegalArgumentException("Unsupported operation: " + operation);
        }
    }

    private byte[] handleInitPositionMap(byte[] data) {
        InitPositionMapRequest request = new InitPositionMapRequest();
        request.readExternal(data, 1);
        InitPositionMapResponse response = new InitPositionMapResponse(state.initializePositionMap());
        byte[] serialized = new byte[response.getSerializedSize()];
        response.writeExternal(serialized, 0);
        return serialized;
    }

    private byte[] handleRegisterAccess(byte[] data) {
        RegisterAccessRequest request = new RegisterAccessRequest();
        request.readExternal(data, 1);
        RegisterAccessResponse response = new RegisterAccessResponse(state.registerAccess());
        byte[] serialized = new byte[response.getSerializedSize()];
        response.writeExternal(serialized, 0);
        return serialized;
    }

    private byte[] handleReadPath(byte[] data) {
        ReadPathRequest request = new ReadPathRequest();
        request.readExternal(data, 1);
        EncryptedORAMPath path = state.readPath(request.getPid());
        ReadPathResponse response = new ReadPathResponse(path);
        byte[] serialized = new byte[response.getSerializedSize()];
        response.writeExternal(serialized, 0);
        return serialized;
    }

    private byte[] handleSubmitTurn(byte[] data) {
        SubmitTurnRequest request = new SubmitTurnRequest();
        request.readExternal(data, 1);
        state.submitTurn(
                request.getSeq(),
                request.getEncryptedPath()
        );
        TurnUpdateResponse response = new TurnUpdateResponse(request.getEncryptedMapUpdate());
        byte[] serialized = new byte[response.getSerializedSize()];
        response.writeExternal(serialized, 0);
        sendPendingTurnUpdates(request.getSeq(), serialized);
        return serialized;
    }

    private byte[] handleDummyTurn(byte[] data) {
        DummyTurnRequest request = new DummyTurnRequest();
        request.readExternal(data, 1);
        // Dummy turns are used to charge the same communication cost as a real
        // turn. They must not mutate the tree, otherwise a stale dummy path can
        // race with and overwrite a real turn.
        return new byte[0];
    }

    private byte[] handleWaitTurnUpdate(int sender, byte[] data) {
        TurnUpdateRequest request = new TurnUpdateRequest();
        request.readExternal(data, 1);

        int seq = request.getSeq();
        int slot = seq % c;
        synchronized (updateLock) {
            if (recentUpdateSeqs[slot] == seq) {
                return recentSerializedUpdates[slot];
            }

            if (pendingWaiterSeqs[slot] != -1 && pendingWaiterSeqs[slot] != seq) {
                throw new IllegalStateException(
                        "Pending waiter slot collision, slot=" + slot +
                                ", existingSeq=" + pendingWaiterSeqs[slot] +
                                ", requestedSeq=" + seq
                );
            }
            pendingWaiterSeqs[slot] = seq;
            pendingUpdateWaiters[slot].add(sender);
            return null;
        }
    }

    private void sendPendingTurnUpdates(int seq, byte[] serializedUpdate) {
        List<Integer> waiters = new ArrayList<>();
        int slot = seq % c;
        synchronized (updateLock) {
            recentUpdateSeqs[slot] = seq;
            recentSerializedUpdates[slot] = serializedUpdate;

            if (pendingWaiterSeqs[slot] == seq) {
                waiters.addAll(pendingUpdateWaiters[slot]);
                pendingUpdateWaiters[slot].clear();
                pendingWaiterSeqs[slot] = -1;
            }
        }

        for (Integer waiter : waiters) {
            sendResponse(waiter, serializedUpdate);
        }
    }
}
