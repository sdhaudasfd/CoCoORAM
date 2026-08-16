package oram.server;

import oram.messages.ConcurMessages;
import oram.messages.InitGbMpRequest;
import oram.messages.InitGbMpResponse;
import oram.utils.ServerOperationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Map;
import java.util.HashMap;

public class ConcurORAMServer extends ServerExecutable {
    private static final Logger logger = LoggerFactory.getLogger("oram");

    private final ConcurORAMState state;
    private final Object currentDrlWaiterLock = new Object();
    private final int[] currentDrlWaiters;
    private int nextCurrentDrlReplyQueryId;

    private final Object evictionWaiterLock = new Object();
    private final Map<Integer, Integer> evictionCriticalWaiters;
    private int nextEvictionIdToRead;
    private boolean noActiveEviction;

    private final Object currentMapWaiterLock = new Object();
    private final Map<Integer, java.util.ArrayList<Integer>> currentMapWaiters;
    private final Object committedMapWaiterLock = new Object();
    private final Map<Integer, java.util.ArrayList<Integer>> committedMapWaiters;

    public ConcurORAMServer(int c,
                            int treeHeight,
                            int stashSize,
                            int z,
                            int s,
                            int A,
                            int blockSize,
                            String ip,
                            int port) throws InterruptedException {
        super(0, ip, port);
        this.state = new ConcurORAMState(c, treeHeight, stashSize, z, s, A, blockSize);
        this.currentDrlWaiters = new int[c];
        this.nextCurrentDrlReplyQueryId = 0;
        this.evictionCriticalWaiters = new HashMap<>();
        this.currentMapWaiters = new HashMap<>();
        this.committedMapWaiters = new HashMap<>();
        this.nextEvictionIdToRead = 0;
        this.noActiveEviction = true;
        logger.info(
                "ConcurORAM server ready, c={}, bidExponent={}, stashSize={}, Z={}, S={}, A={}, blockSize={}",
                c, treeHeight - 1, stashSize, z, s, A, blockSize
        );
    }

    public static void main(String[] args) throws InterruptedException {

        if (args.length != 9) {
            System.out.println("Usage: oram.server.ConcurORAMServer " +
                    "<c> <bidExponent> <stashSize> <Z> <S> <A> <blockSize> <ip> <port>");
            System.exit(-1);
        }

        new ConcurORAMServer(
                Integer.parseInt(args[0]),
                Integer.parseInt(args[1]) + 1,
                Integer.parseInt(args[2]),
                Integer.parseInt(args[3]),
                Integer.parseInt(args[4]),
                Integer.parseInt(args[5]),
                Integer.parseInt(args[6]),
                args[7],
                Integer.parseInt(args[8])
        );
    }

    @Override
    public byte[] execute(int sender, byte[] data) {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("Empty request");
        }

        ServerOperationType operation = ServerOperationType.getOperation(data[0]);
        switch (operation) {
            case INIT_GBMP:
                return handleInitGbMp(data);
            case CONCUR_REGISTER_QUERY:
                return handleRegisterQuery(data);
            case CONCUR_READ_LOGS_AND_STASHES:
                return handleReadLogsAndStashes();
            case CONCUR_READ_DATA_PATH:
                return handleReadDataPath(data);
            case CONCUR_READ_CURRENT_DRL:
                return handleReadCurrentDrl(sender, data);
            case CONCUR_WRITE_QUERY_RESULT:
                return handleWriteQueryResult(data);
            case CONCUR_FINALIZE_ROUND:
                return handleFinalizeRound();
            case CONCUR_READ_EVICTION_INPUT:
                return handleReadEvictionInput(data);
            case CONCUR_READ_EVICTION_CRITICAL:
                return handleReadEvictionCritical(sender, data);
            case CONCUR_SUBMIT_EVICTION:
                return handleSubmitEviction(data);
            case CONCUR_COMMIT_READY:
                return empty();
            case CONCUR_READ_COMMITTED_MAP_UPDATES:
                return handleReadCommittedMapUpdates(sender, data);
            case CONCUR_READ_MAP_UPDATES:
                return handleReadMapUpdates(sender, data);
            default:
                throw new IllegalArgumentException("Unsupported ConcurORAM operation: " + operation);
        }
    }

    private byte[] handleInitGbMp(byte[] data) {
        InitGbMpRequest request = new InitGbMpRequest();
        request.readExternal(data, 1);
        return serialize(new InitGbMpResponse(state.encryptedPositionMap));
    }

    private byte[] handleRegisterQuery(byte[] data) {
        ConcurMessages.RegisterQueryRequest request = new ConcurMessages.RegisterQueryRequest();
        request.readExternal(data, 1);
        ConcurORAMState.RegisterResult result = state.registerQuery(request.getEncryptedAddr());
        return serialize(new ConcurMessages.RegisterQueryResponse(
                result.getQueryId(),
                result.getRoundId(),
                result.getPriorQl()
        ));
    }

    private byte[] handleReadLogsAndStashes() {
        return serialize(new ConcurMessages.LogsAndStashesResponse(
                state.readDrlSetBlocks(),
                state.readCommittedMainStash(),
                state.readStashSet()
        ));
    }

    private byte[] handleReadDataPath(byte[] data) {
        ConcurMessages.ReadPathSlotRequest request = new ConcurMessages.ReadPathSlotRequest();
        request.readExternal(data, 1);
        return serialize(new ConcurMessages.BlockResponse(state.readDataBlock(request.getPid(), request.getSlots())));
    }

    private byte[] handleReadCurrentDrl(int sender, byte[] data) {
        ConcurMessages.IntRequest request = new ConcurMessages.IntRequest();
        request.readExternal(data, 1);
        int queryId = request.getValue();
        synchronized (currentDrlWaiterLock) {
            if (queryId >= currentDrlWaiters.length) {
                throw new IllegalArgumentException("Query id too large for waiter table: " + queryId);
            }
            currentDrlWaiters[queryId] = sender;
            flushCurrentDrlWaitersLocked();
        }
        return null;
    }
    
    private byte[] handleReadMapUpdates(int sender, byte[] data) {
        ConcurMessages.IntRequest request = new ConcurMessages.IntRequest();
        request.readExternal(data, 1);

        int targetRoundId = request.getValue();

        synchronized (currentMapWaiterLock) {
            ConcurORAMState.PublishedMap publishedMap =
                    state.tryReadCurrentMapUpdates(targetRoundId);

            if (publishedMap != null) {
                return serialize(new ConcurMessages.MapUpdatesResponse(
                        publishedMap.getRoundId(),
                        publishedMap.getEntries()
                ));
            }

            java.util.ArrayList<Integer> waiters = currentMapWaiters.get(targetRoundId);
            if (waiters == null) {
                waiters = new java.util.ArrayList<>();
                currentMapWaiters.put(targetRoundId, waiters);
            }

            waiters.add(sender);
        }

        return null;
    }

    private byte[] handleWriteQueryResult(byte[] data) {
        ConcurMessages.WriteQueryResultRequest request = new ConcurMessages.WriteQueryResultRequest();
        request.readExternal(data, 1);

        state.writeQueryResult(
                request.getQueryId(),
                request.getEncryptedBlock(),
                request.getEncryptedMapUpdate()
        );

        synchronized (currentDrlWaiterLock) {
            flushCurrentDrlWaitersLocked();
        }

        synchronized (currentMapWaiterLock) {
            flushCurrentMapWaitersLocked();
        }

        return empty();
    }
    
    private void flushCurrentDrlWaitersLocked() {
        if (nextCurrentDrlReplyQueryId >= currentDrlWaiters.length || 
                currentDrlWaiters[nextCurrentDrlReplyQueryId] == 0 ||
                !state.isCurrentDrlPrefixReady(nextCurrentDrlReplyQueryId)) {
            return;
        }
        byte[] response = serialize(new ConcurMessages.ByteMatrixResponse(state.readCurrentDrl()));
        sendResponse(currentDrlWaiters[nextCurrentDrlReplyQueryId], response);
        nextCurrentDrlReplyQueryId++;
    }

    private void flushCurrentMapWaitersLocked() {
        java.util.ArrayList<Integer> rounds =
                new java.util.ArrayList<>(currentMapWaiters.keySet());

        for (Integer roundId : rounds) {
            ConcurORAMState.PublishedMap publishedMap =
                    state.tryReadCurrentMapUpdates(roundId);

            if (publishedMap == null) {
                continue;
            }

            byte[] response = serialize(new ConcurMessages.MapUpdatesResponse(
                    publishedMap.getRoundId(),
                    publishedMap.getEntries()
            ));

            java.util.ArrayList<Integer> waiters = currentMapWaiters.remove(roundId);
            if (waiters == null) {
                continue;
            }

            for (Integer waiter : waiters) {
                sendResponse(waiter, response);
            }
        }
    }

    private byte[] handleFinalizeRound() {
        ConcurORAMState.FinalizeResult result = state.finalizeRoundAndCommitReady();

        nextCurrentDrlReplyQueryId = 0;
        Arrays.fill(currentDrlWaiters, 0);

        synchronized (currentMapWaiterLock) {
            flushCurrentMapWaitersLocked();
        }

        synchronized (committedMapWaiterLock) {
            flushCommittedMapWaitersLocked();
        }

        return serialize(new ConcurMessages.FinalizeRoundResponse(
                result.getRoundId(),
                result.getCommittedMapUpdates()
        ));
    }

    private byte[] handleReadEvictionInput(byte[] data) {
        ConcurMessages.EvictionInputRequest request = new ConcurMessages.EvictionInputRequest();
        request.readExternal(data, 1);

        ConcurORAMState.EvictionInput input = state.readEvictionInput(
                request.getRoundId(),
                request.getFromQueryId(),
                request.getToQueryId()
        );

        return serialize(new ConcurMessages.EvictionInputResponse(
                input.getEvictionId(),
                input.getRoundId(),
                input.getPid(),
                input.getDrlBlocks(),
                input.getNonCriticalBuckets()
        ));
    }

    private byte[] handleReadEvictionCritical(int sender, byte[] data) {
        ConcurMessages.IntRequest request = new ConcurMessages.IntRequest();
        request.readExternal(data, 1);

        int evictionId = request.getValue();

        ConcurORAMState.EvictionCriticalInput input =
                state.readEvictionCritical(evictionId);

        return serialize(new ConcurMessages.EvictionCriticalResponse(
                input.getTempStashBlocks(),
                input.getCriticalBuckets()
        ));
    }

    private byte[] handleSubmitEviction(byte[] data) {
        ConcurMessages.SubmitEvictionRequest request = new ConcurMessages.SubmitEvictionRequest();
        request.readExternal(data, 1);

        synchronized (evictionWaiterLock) {
            state.submitEviction(
                    request.getRoundId(), 
                    request.getPath(),
                    request.getTempStashBlocks(),
                    request.getMapUpdates()
            );
        }

        return empty();
    }

    private void flushEvictionCriticalWaitersLocked() {
        if (!noActiveEviction) {
            return;
        }

        Integer sender = evictionCriticalWaiters.get(nextEvictionIdToRead);
        if (sender == null) {
            return;
        }

        ConcurORAMState.EvictionCriticalInput input =
                state.readEvictionCritical(nextEvictionIdToRead);

        byte[] response = serialize(new ConcurMessages.EvictionCriticalResponse(
                input.getTempStashBlocks(),
                input.getCriticalBuckets()
        ));

        noActiveEviction = false;
        evictionCriticalWaiters.remove(nextEvictionIdToRead);
        sendResponse(sender, response);
    }

    private void flushCommittedMapWaitersLocked() {
        java.util.ArrayList<Integer> rounds =
                new java.util.ArrayList<>(committedMapWaiters.keySet());

        for (Integer roundId : rounds) {
            byte[][] updates = state.tryReadCommittedMapUpdates(roundId);

            if (updates == null) {
                continue;
            }

            byte[] response = serialize(new ConcurMessages.MapUpdatesResponse(
                    roundId,
                    updates
            ));

            java.util.ArrayList<Integer> waiters = committedMapWaiters.remove(roundId);
            if (waiters == null) {
                continue;
            }

            for (Integer waiter : waiters) {
                sendResponse(waiter, response);
            }
        }
    }

    private byte[] handleReadCommittedMapUpdates(int sender, byte[] data) {
        ConcurMessages.IntRequest request = new ConcurMessages.IntRequest();
        request.readExternal(data, 1);

        int targetRoundId = request.getValue();

        synchronized (committedMapWaiterLock) {
            byte[][] updates = state.tryReadCommittedMapUpdates(targetRoundId);

            if (updates != null) {
                return serialize(new ConcurMessages.MapUpdatesResponse(
                        targetRoundId,
                        updates
                ));
            }

            java.util.ArrayList<Integer> waiters = committedMapWaiters.get(targetRoundId);
            if (waiters == null) {
                waiters = new java.util.ArrayList<>();
                committedMapWaiters.put(targetRoundId, waiters);
            }

            waiters.add(sender);
        }

        return null;
    }

    private byte[] empty() {
        return serialize(new ConcurMessages.Empty());
    }

    private byte[] serialize(oram.utils.RawCustomExternalizable response) {
        byte[] out = new byte[response.getSerializedSize()];
        response.writeExternal(out, 0);
        return out;
    }
}
