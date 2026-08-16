package taostore.server;

import comunication.Message;
import comunication.MessageProcessor;
import comunication.server.ServerCommunicationSystem;
import oram.common.Bucket;
import oram.common.ORAMContext;
import oram.common.ORAMUtils;
import oram.common.Status;
import oram.security.EncryptionManager;
import oram.server.structure.EncryptedBucket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import taostore.messages.InitOramRequestMessage;
import taostore.messages.InitOramResponseMessage;
import taostore.messages.MessageTypes;
import taostore.messages.ReadPathRequestMessage;
import taostore.messages.ReadPathResponseMessage;
import taostore.messages.WriteBackAckMessage;
import taostore.messages.WriteBackRequestMessage;
import taostore.server.structure.TimestampedBucket;

import java.io.IOException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.Map;

public class StorageServer {
    private final Logger logger = LoggerFactory.getLogger("taostore.server");
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
        MessageProcessor initProcessor = new MessageProcessor(MessageTypes.INIT_ORAM_REQUEST) {
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

        serverCommunicationSystem.registerMessageListener(MessageTypes.INIT_ORAM_REQUEST, initProcessor);
        serverCommunicationSystem.registerMessageListener(MessageTypes.READ_PATH_REQUEST, readProcessor);
        serverCommunicationSystem.registerMessageListener(MessageTypes.WRITE_BACK_REQUEST, writeProcessor);
    }

    private void onInit(Message message) {
        InitOramRequestMessage req = InitOramRequestMessage.fromBytes(message.getSerializedMessage());
        this.context = new ORAMContext(req.getTreeHeight(), req.getBucketSize(), req.getBlockSize());

        int treeSize = context.getTreeSize();
        TimestampedBucket[] newTree = new TimestampedBucket[treeSize];
        for (int i = 0; i < treeSize; i++) {
            Bucket bucket = new Bucket(context.getBucketSize(), context.getBlockSize(), i);
            EncryptedBucket encrypted = encryptionManager.encryptBucket(context, bucket);
            newTree[i] = new TimestampedBucket(encrypted, 0);
        }
        this.tree = newTree;

        InitOramResponseMessage resp = new InitOramResponseMessage(Status.SUCCESS);
        sendResponse(message.getSender(), MessageTypes.INIT_ORAM_RESPONSE, resp.toBytes());
        logger.info("Storage initialized: treeHeight={}, bucketSize={}, blockSize={}",
                context.getTreeHeight(), context.getBucketSize(), context.getBlockSize());
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
                    new WriteBackAckMessage(req.getServerTimestamp()).toBytes());
            return;
        }

        int ts = req.getServerTimestamp();
        for (Map.Entry<Integer, EncryptedBucket[]> entry : req.getPaths().entrySet()) {
            int pathId = entry.getKey();
            EncryptedBucket[] incomingBuckets = entry.getValue();
            int[] locations = ORAMUtils.computePathLocations(pathId, context.getTreeHeight());
            int bound = Math.min(locations.length, incomingBuckets.length);

            for (int i = 0; i < bound; i++) {
                int loc = locations[i];
                TimestampedBucket tb = tree[loc];
                tb.getLock().writeLock().lock();
                try {
                    if (tb.getTimestamp() < ts) {
                        tb.overwrite(incomingBuckets[i], ts);
                    }
                } finally {
                    tb.getLock().writeLock().unlock();
                }
            }
        }

        sendResponse(message.getSender(), MessageTypes.WRITE_BACK_ACK, new WriteBackAckMessage(ts).toBytes());
    }

    private void sendResponse(int target, int type, byte[] payload) {
        serverCommunicationSystem.sendMessage(target, new Message(processId, type, payload));
    }

    public static void main(String[] args) {
        if (args.length != 6) {
            System.out.println("Usage: taostore.server.StorageServer <ip> <port> <treeHeight> <bucketSize> <blockSize> <processId>");
            System.exit(-1);
        }
        String ip = args[0];
        int port = Integer.parseInt(args[1]);
        int processId = Integer.parseInt(args[5]);
        new StorageServer(ip, port, processId);
    }
}
