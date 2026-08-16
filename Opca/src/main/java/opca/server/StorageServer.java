package opca.server;

import comunication.Message;
import comunication.MessageProcessor;
import comunication.server.ServerCommunicationSystem;
import opca.messages.InitRequestMessage;
import opca.messages.InitResponseMessage;
import opca.messages.MessageTypes;
import opca.messages.ReadPathRequestMessage;
import opca.messages.ReadPathResponseMessage;
import opca.messages.WriteBackAckMessage;
import opca.messages.WriteBackRequestMessage;
import opca.server.structure.TimestampedBucket;
import oram.common.Bucket;
import oram.common.ORAMContext;
import oram.common.ORAMUtils;
import oram.common.Status;
import oram.security.EncryptionManager;
import oram.server.structure.EncryptedBucket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.Map;

public class StorageServer {
    private final Logger logger = LoggerFactory.getLogger("opca.server");
    private final int processId;
    private final ServerCommunicationSystem serverCommunicationSystem;
    private final EncryptionManager encryptionManager;

    private volatile ORAMContext context;
    private volatile TimestampedBucket[] tree;

    public StorageServer(String ip, int port, int processId) {
        this.processId = processId;
        this.encryptionManager = new EncryptionManager();

        try {
            this.serverCommunicationSystem = new ServerCommunicationSystem(processId, ip, port, 16, 100_000_000);
        } catch (InterruptedException | IOException | CertificateException | UnrecoverableKeyException
                 | NoSuchAlgorithmException | KeyStoreException e) {
            throw new RuntimeException("Failed to start storage server", e);
        }

        registerAndStartProcessors();
        logger.info("Storage server listening on {}:{}", ip, port);
    }

    private void registerAndStartProcessors() {
        MessageProcessor initProcessor = new MessageProcessor(MessageTypes.INIT_REQUEST) {
            @Override
            public void deliverMessage(Message message) {
                onInit(message);
            }
        };
        MessageProcessor readProcessor = new MessageProcessor(MessageTypes.READ_PATH_REQUEST) {
            @Override
            public void deliverMessage(Message message) {
                onReadPath(message);
            }
        };
        MessageProcessor writeProcessor = new MessageProcessor(MessageTypes.WRITE_BACK_REQUEST) {
            @Override
            public void deliverMessage(Message message) {
                onWriteBack(message);
            }
        };

        initProcessor.start();
        readProcessor.start();
        writeProcessor.start();

        serverCommunicationSystem.registerMessageListener(MessageTypes.INIT_REQUEST, initProcessor);
        serverCommunicationSystem.registerMessageListener(MessageTypes.READ_PATH_REQUEST, readProcessor);
        serverCommunicationSystem.registerMessageListener(MessageTypes.WRITE_BACK_REQUEST, writeProcessor);
    }

    private void onInit(Message message) {
        InitRequestMessage req = InitRequestMessage.fromBytes(message.getSerializedMessage());
        long initStartNs = System.nanoTime();
        logger.info("Initializing storage: treeHeight={}, bucketSize={}, blockSize={}",
            req.getTreeHeight(), req.getBucketSize(), req.getBlockSize());
        this.context = new ORAMContext(req.getTreeHeight(), req.getBucketSize(), req.getBlockSize());

        int treeSize = context.getTreeSize();
        TimestampedBucket[] newTree = new TimestampedBucket[treeSize];
        for (int i = 0; i < treeSize; i++) {
            Bucket bucket = new Bucket(context.getBucketSize(), context.getBlockSize(), i);
            EncryptedBucket encrypted = encryptionManager.encryptBucket(context, bucket);
            newTree[i] = new TimestampedBucket(encrypted, 0);
        }
        this.tree = newTree;

        InitResponseMessage resp = new InitResponseMessage(Status.SUCCESS);
        sendResponse(message.getSender(), MessageTypes.INIT_RESPONSE, resp.toBytes());
        long initElapsedMs = (System.nanoTime() - initStartNs) / 1_000_000;
        logger.info("Storage initialized: treeHeight={}, bucketSize={}, blockSize={}",
                context.getTreeHeight(), context.getBucketSize(), context.getBlockSize());
        logger.info("Storage initialization took {} ms", initElapsedMs);
    }

    private void onReadPath(Message message) {
        ReadPathRequestMessage req = ReadPathRequestMessage.fromBytes(message.getSerializedMessage());
        if (context == null || tree == null) {
            logger.warn("ReadPath before initialization");
            sendResponse(message.getSender(), MessageTypes.READ_PATH_RESPONSE,
                    new ReadPathResponseMessage(req.getCorrelationId(), req.getPathId(), new EncryptedBucket[0]).toBytes());
            return;
        }

        int[] locations = ORAMUtils.computePathLocations(req.getPathId(), context.getTreeHeight());
        EncryptedBucket[] buckets = new EncryptedBucket[locations.length];

        for (int i = 0; i < locations.length; i++) {
            int loc = locations[i];
            TimestampedBucket tb = tree[loc];
            tb.getLock().readLock().lock();
            try {
                buckets[i] = tb.getBucket();
            } finally {
                tb.getLock().readLock().unlock();
            }
        }

        ReadPathResponseMessage resp = new ReadPathResponseMessage(req.getCorrelationId(), req.getPathId(), buckets);
        sendResponse(message.getSender(), MessageTypes.READ_PATH_RESPONSE, resp.toBytes());
    }

    private void onWriteBack(Message message) {
        WriteBackRequestMessage req = WriteBackRequestMessage.fromBytes(message.getSerializedMessage());
        if (context == null || tree == null) {
            logger.warn("WriteBack before initialization");
            sendResponse(message.getSender(), MessageTypes.WRITE_BACK_ACK,
                    new WriteBackAckMessage(req.getWriteBackRound()).toBytes());
            return;
        }

        int round = req.getWriteBackRound();
        for (Map.Entry<Integer, EncryptedBucket> entry : req.getBuckets().entrySet()) {
            int bucketId = entry.getKey();
            if (bucketId < 0 || bucketId >= tree.length) {
                continue;
            }

            TimestampedBucket tb = tree[bucketId];
            tb.getLock().writeLock().lock();
            try {
                if (tb.getTimestamp() < round) {
                    tb.overwrite(entry.getValue(), round);
                }
            } finally {
                tb.getLock().writeLock().unlock();
            }
        }

        sendResponse(message.getSender(), MessageTypes.WRITE_BACK_ACK, new WriteBackAckMessage(round).toBytes());
    }

    private void sendResponse(int target, int type, byte[] payload) {
        serverCommunicationSystem.sendMessage(target, new Message(processId, type, payload));
    }

    public static void main(String[] args) {
        if (args.length != 6) {
            System.out.println("Usage: opca.server.StorageServer <ip> <port> <treeHeight> <bucketSize> <blockSize> <processId>");
            System.exit(-1);
        }
        String ip = args[0];
        int port = Integer.parseInt(args[1]);
        int processId = Integer.parseInt(args[5]);
        new StorageServer(ip, port, processId);
    }
}
