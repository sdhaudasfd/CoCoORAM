package oram.server;

import oram.messages.ReadPathRequest;
import oram.messages.ReadPathResponse;
import oram.messages.RegisterAccessResponse;
import oram.messages.SubmitTurnRequest;
import oram.messages.TurnUpdateResponse;
import oram.structure.EncryptedMapUpdate;
import oram.structure.EncryptedORAMPath;
import oram.utils.ServerOperationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.Queue;

public final class BlockSSEServer extends ServerExecutable {
    private static final Logger logger = LoggerFactory.getLogger("oram");

    private final BlockSSEState state;
    private final Object leaseLock = new Object();
    private final Queue<Integer> searchWaiters = new ArrayDeque<>();
    private int activeSearcher = -1;

    public BlockSSEServer(int bidExponent,
                          int rootBucketSize,
                          int bucketSize,
                          int blockSize,
                          String ip,
                          int port,
                          String datasetPath) throws Exception {
        super(0, ip, port);
        this.state = new BlockSSEState(
                bidExponent,
                rootBucketSize,
                bucketSize,
                blockSize,
                Paths.get(datasetPath)
        );
        logger.info(
                "BlockSSE server ready, treeHeight={}, rootBucketSize={}, bucketSize={}, " +
                        "blockSize={}, keywords={}, chunks={}",
                bidExponent + 1,
                rootBucketSize,
                bucketSize,
                blockSize,
                state.getIndex().getKeywordIds().size(),
                state.getIndex().getChunkCount()
        );
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 7) {
            System.out.println("Usage: oram.server.BlockSSEServer " +
                    "<bidExponent> <rootBucketSize> <bucketSize> <blockSize> " +
                    "<ip> <port> <datasetPath>");
            System.exit(-1);
        }
        new BlockSSEServer(
                Integer.parseInt(args[0]),
                Integer.parseInt(args[1]),
                Integer.parseInt(args[2]),
                Integer.parseInt(args[3]),
                args[4],
                Integer.parseInt(args[5]),
                args[6]
        );
    }

    @Override
    public byte[] execute(int sender, byte[] data) {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("Empty request");
        }
        ServerOperationType operation = ServerOperationType.getOperation(data[0]);
        switch (operation) {
            case ACQUIRE_SEARCH:
                return acquireSearch(sender);
            case RELEASE_SEARCH:
                return releaseSearch(sender);
            case REGISTER_ACCESS:
                synchronized (leaseLock) {
                    requireLease(sender);
                }
                return serialize(new RegisterAccessResponse(state.registerAccess()));
            case READ_PATH:
                return readPath(sender, data);
            case SUBMIT_TURN:
                return submitTurn(sender, data);
            default:
                throw new IllegalArgumentException("Unsupported BlockSSE operation: " + operation);
        }
    }

    private byte[] acquireSearch(int sender) {
        synchronized (leaseLock) {
            if (activeSearcher == -1) {
                activeSearcher = sender;
                return serialize(new RegisterAccessResponse(1));
            }
            if (activeSearcher == sender || searchWaiters.contains(sender)) {
                throw new IllegalStateException("Duplicate search lease request from client " + sender);
            }
            searchWaiters.add(sender);
            return null;
        }
    }

    private byte[] releaseSearch(int sender) {
        Integer next = null;
        synchronized (leaseLock) {
            requireLease(sender);
            activeSearcher = -1;
            if (!searchWaiters.isEmpty()) {
                next = searchWaiters.remove();
                activeSearcher = next;
            }
        }
        if (next != null) {
            sendResponse(next, serialize(new RegisterAccessResponse(1)));
        }
        return serialize(new RegisterAccessResponse(1));
    }

    private byte[] readPath(int sender, byte[] data) {
        synchronized (leaseLock) {
            requireLease(sender);
        }
        ReadPathRequest request = new ReadPathRequest();
        request.readExternal(data, 1);
        EncryptedORAMPath path = state.readPath(request.getPid());
        ReadPathResponse response = new ReadPathResponse(path);
        byte[] serialized = new byte[response.getSerializedSize()];
        response.writeExternal(serialized, 0);
        return serialized;
    }

    private byte[] submitTurn(int sender, byte[] data) {
        synchronized (leaseLock) {
            requireLease(sender);
        }
        SubmitTurnRequest request = new SubmitTurnRequest();
        request.readExternal(data, 1);
        state.submitTurn(request.getEncryptedPath());
        TurnUpdateResponse response = new TurnUpdateResponse(new EncryptedMapUpdate(new byte[0]));
        byte[] serialized = new byte[response.getSerializedSize()];
        response.writeExternal(serialized, 0);
        return serialized;
    }

    private void requireLease(int sender) {
        if (activeSearcher != sender) {
            throw new IllegalStateException(
                    "Client " + sender + " does not hold the search lease; holder=" + activeSearcher
            );
        }
    }

    private static byte[] serialize(RegisterAccessResponse response) {
        byte[] serialized = new byte[response.getSerializedSize()];
        response.writeExternal(serialized, 0);
        return serialized;
    }
}
