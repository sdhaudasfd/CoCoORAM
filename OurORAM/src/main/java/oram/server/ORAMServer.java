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

    public ORAMServer(int c,
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

        logger.info(
                "ORAM server ready, c={}, treeHeight={}, rootBucketSize={}, competitionBucketSize={}, bucketSize={}, blockSize={}, bidSpace={}",
                c, treeHeight, rootBucketSize, competitionBucketSize, bucketSize, blockSize, bidSpace
        );
        logger.info("Server total storage[bytes]: {}", oramState.getServerTotalStorageBytes());
    }

    public static void main(String[] args) throws InterruptedException {
        if (args.length != 8) {
            System.out.println("Usage: oram.server.ORAMServer " +
                    "<c> <bidExponent> <rootBucketSize> <competitionBucketSize> <bucketSize> <blockSize> <ip> <port>");
            System.exit(-1);
        }

        int c = Integer.parseInt(args[0]);
        int bidExponent = Integer.parseInt(args[1]);
        int rootBucketSize = Integer.parseInt(args[2]);
        int competitionBucketSize = Integer.parseInt(args[3]);
        int bucketSize = Integer.parseInt(args[4]);
        int blockSize = Integer.parseInt(args[5]);
        String ip = args[6];
        int port = Integer.parseInt(args[7]);

        new ORAMServer(c, bidExponent, rootBucketSize, competitionBucketSize, bucketSize, blockSize, ip, port);
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
                return handleRound1Query(sender, data);
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

    private byte[] handleRound1Query(int sender, byte[] data) {
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

        byte[] serializedResponse = new byte[response.getSerializedSize()];
        response.writeExternal(serializedResponse, 0);
        return serializedResponse;
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
        
        Round3SubmitEvictionResponse response = new Round3SubmitEvictionResponse(true);
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

    public void shutdown() {
        serverCommunicationSystem.shutdown();
        interrupt();
    }
}
