package opca.proxy;

import comunication.Message;
import comunication.MessageProcessor;
import comunication.server.ServerCommunicationSystem;
import opca.messages.ClientRequestMessage;
import opca.messages.DrainRequestMessage;
import opca.messages.DrainResponseMessage;
import opca.messages.InitRequestMessage;
import opca.messages.InitResponseMessage;
import opca.messages.MessageTypes;
import opca.messages.ReadPathResponseMessage;
import opca.messages.StateChangeNoticeMessage;
import opca.messages.WriteBackAckMessage;
import opca.proxy.structure.LocalSubtree;
import opca.proxy.structure.OpcaBuffer;
import opca.proxy.structure.OpcaMap;
import opca.proxy.structure.PositionMap;
import oram.common.ORAMContext;
import oram.common.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class OpcaProxy {
    private static final Logger logger = LoggerFactory.getLogger("opca.proxy");

    public static final int STATE_READ_ONLY = 0;
    public static final int STATE_WRITE_ONLY = 1;

    private static final long INIT_TIMEOUT_SECONDS = 180;

    private final AtomicInteger currentState;
    private final ReentrantReadWriteLock stateLock;

    private final PositionMap positionMap;
    private final OpcaMap opcaMap;
    private final OpcaBuffer opcaBuffer;
    private final LocalSubtree localSubtree;
    private final OpcaScheduler scheduler;
    private final OpcaCommitter committer;

    private final ORAMContext context;
    private final int k;

    private final ServerCommunicationSystem communicationSystem;
    private final int serverId;
    private final int proxyId;

    private final ConcurrentHashMap<Long, CompletableFuture<ReadPathResponseMessage>> pendingReads;
    private final AtomicLong correlationCounter;
    private final ConcurrentHashMap<Integer, CompletableFuture<WriteBackAckMessage>> pendingWriteBackAcks;
    private final AtomicLong writeBackRound;

    private final Set<Integer> knownClientIds;

    private OpcaProxy(ServerCommunicationSystem communicationSystem,
                      ORAMContext context,
                      int k,
                      int proxyId,
                      int serverId) {
        this.currentState = new AtomicInteger(STATE_READ_ONLY);
        this.stateLock = new ReentrantReadWriteLock(true);

        this.context = context;
        this.k = k;
        this.proxyId = proxyId;
        this.serverId = serverId;

        this.communicationSystem = communicationSystem;
        this.pendingReads = new ConcurrentHashMap<>();
        this.correlationCounter = new AtomicLong(1);
        this.pendingWriteBackAcks = new ConcurrentHashMap<>();
        this.writeBackRound = new AtomicLong(1);
        this.knownClientIds = ConcurrentHashMap.newKeySet();

        this.positionMap = new PositionMap(context.getTreeSize(), context.getTreeHeight());
        this.opcaMap = new OpcaMap();
        this.opcaBuffer = new OpcaBuffer();
        this.localSubtree = new LocalSubtree(context);

        this.scheduler = new OpcaScheduler(
                positionMap,
                opcaMap,
                opcaBuffer,
                localSubtree,
                context,
                communicationSystem,
                communicationSystem,
                proxyId,
                serverId,
                pendingReads,
                correlationCounter
        );
        this.committer = new OpcaCommitter(
                positionMap,
                opcaMap,
                opcaBuffer,
                localSubtree,
                context,
                communicationSystem,
                serverId,
                proxyId,
                pendingReads,
                correlationCounter,
                pendingWriteBackAcks
        );
    }

    public static void main(String[] args) {
        if (args.length != 9) {
            System.out.println("Usage: opca.proxy.OpcaProxy <proxyIp> <proxyPort> <serverIp> <serverPort> <treeHeight> <bucketSize> <blockSize> <k> <processId>");
            System.exit(-1);
        }

        String proxyIp = args[0];
        int proxyPort = Integer.parseInt(args[1]);
        String serverIp = args[2];
        int serverPort = Integer.parseInt(args[3]);
        int treeHeight = Integer.parseInt(args[4]);
        int bucketSize = Integer.parseInt(args[5]);
        int blockSize = Integer.parseInt(args[6]);
        int k = Integer.parseInt(args[7]);
        int processId = Integer.parseInt(args[8]);
        int serverId = 0;

        try {
            ServerCommunicationSystem communicationSystem = new ServerCommunicationSystem(
                    processId,
                    proxyIp,
                    proxyPort,
                    16,
                    100_000_000
            );

            CompletableFuture<InitResponseMessage> initFuture = new CompletableFuture<>();
            MessageProcessor initResponseProcessor = new MessageProcessor(MessageTypes.INIT_RESPONSE) {
                @Override
                public void deliverMessage(Message message) {
                    initFuture.complete(InitResponseMessage.fromBytes(message.getSerializedMessage()));
                }
            };
            initResponseProcessor.start();
            communicationSystem.registerMessageListener(MessageTypes.INIT_RESPONSE, initResponseProcessor);

            communicationSystem.connectTo(serverId, serverIp, serverPort);
            InitRequestMessage initRequest = new InitRequestMessage(treeHeight, bucketSize, blockSize);
            logger.info("Sending INIT_REQUEST to storage {}:{} (treeHeight={}, bucketSize={}, blockSize={})",
                    serverIp, serverPort, treeHeight, bucketSize, blockSize);
            communicationSystem.sendMessage(serverId, new Message(processId, MessageTypes.INIT_REQUEST, initRequest.toBytes()));

            InitResponseMessage initResponse;
            try {
                initResponse = initFuture.get(INIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                throw new TimeoutException("Timed out waiting " + INIT_TIMEOUT_SECONDS +
                        "s for INIT_RESPONSE from storage " + serverIp + ":" + serverPort +
                        ". Storage initialization may still be building the ORAM tree.");
            }
            if (initResponse.getStatus() != Status.SUCCESS) {
                throw new IllegalStateException("Failed to initialize storage server");
            }

            logger.info("Received INIT_RESPONSE from storage server");

            ORAMContext context = new ORAMContext(treeHeight, bucketSize, blockSize);
            OpcaProxy proxy = new OpcaProxy(communicationSystem, context, k, processId, serverId);
            proxy.start();

            while (true) {
                Thread.sleep(60_000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Proxy interrupted", e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to start Opca proxy", e);
        }
    }

    private void start() {
        registerHandlers();
        startStateMonitor();
        startReporter();
        logger.info("Opca proxy ready");
    }

    private void registerHandlers() {
        MessageProcessor clientRequestHandler = new MessageProcessor(MessageTypes.CLIENT_REQUEST) {
            @Override
            public void deliverMessage(Message message) {
                ClientRequestMessage request = ClientRequestMessage.fromBytes(message.getSerializedMessage());
                int clientId = message.getSender();
                knownClientIds.add(clientId);
                onClientRequest(clientId, request);
            }
        };
        clientRequestHandler.start();
        communicationSystem.registerMessageListener(MessageTypes.CLIENT_REQUEST, clientRequestHandler);

        MessageProcessor drainRequestHandler = new MessageProcessor(MessageTypes.DRAIN_REQUEST) {
            @Override
            public void deliverMessage(Message message) {
                DrainRequestMessage request = DrainRequestMessage.fromBytes(message.getSerializedMessage());
                int clientId = message.getSender();
                try {
                    drain();
                } catch (Exception e) {
                    logger.error("Failed to drain OPCA proxy", e);
                }
                DrainResponseMessage response = new DrainResponseMessage(request.getRequestId());
                communicationSystem.sendMessage(clientId, new Message(proxyId, MessageTypes.DRAIN_RESPONSE, response.toBytes()));
            }
        };
        drainRequestHandler.start();
        communicationSystem.registerMessageListener(MessageTypes.DRAIN_REQUEST, drainRequestHandler);

        MessageProcessor readPathResponseHandler = new MessageProcessor(MessageTypes.READ_PATH_RESPONSE) {
            @Override
            public void deliverMessage(Message message) {
                ReadPathResponseMessage response = ReadPathResponseMessage.fromBytes(message.getSerializedMessage());
                CompletableFuture<ReadPathResponseMessage> future = pendingReads.remove(response.getCorrelationId());
                if (future != null) {
                    future.complete(response);
                }
            }
        };
        readPathResponseHandler.start();
        communicationSystem.registerMessageListener(MessageTypes.READ_PATH_RESPONSE, readPathResponseHandler);

        MessageProcessor writeBackAckHandler = new MessageProcessor(MessageTypes.WRITE_BACK_ACK) {
            @Override
            public void deliverMessage(Message message) {
                WriteBackAckMessage ack = WriteBackAckMessage.fromBytes(message.getSerializedMessage());
                CompletableFuture<WriteBackAckMessage> future = pendingWriteBackAcks.remove(ack.getWriteBackRound());
                if (future != null) {
                    future.complete(ack);
                }
            }
        };
        writeBackAckHandler.start();
        communicationSystem.registerMessageListener(MessageTypes.WRITE_BACK_ACK, writeBackAckHandler);
    }

    private void drain() throws Exception {
        stateLock.writeLock().lock();
        try {
            currentState.set(STATE_WRITE_ONLY);
            broadcastStateChange(STATE_WRITE_ONLY);
            try {
                scheduler.awaitIdle();
                if (localSubtree.size() > 0 || opcaMap.totalEntries() > 0 || opcaBuffer.size() > 0) {
                    committer.commitAndUpdate((int) writeBackRound.getAndIncrement());
                }
            } finally {
                currentState.set(STATE_READ_ONLY);
                broadcastStateChange(STATE_READ_ONLY);
            }
        } finally {
            stateLock.writeLock().unlock();
        }
    }

    private void startStateMonitor() {
        Thread stateMonitor = new Thread(() -> {
            while (true) {
                try {
                    if (localSubtree.getAccessedPathNumber() >= k) {
                        logger.info("Accessed path number {} reached threshold {}, triggering write-only state", localSubtree.getAccessedPathNumber(), k);
                        triggerWriteOnlyState();
                    }
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    logger.error("Failed in state monitor", e);
                }
            }
        }, "opca-state-monitor");
        stateMonitor.setDaemon(true);
        stateMonitor.start();
    }

    private void triggerWriteOnlyState() {
        stateLock.writeLock().lock();
        try {
            if (localSubtree.getAccessedPathNumber() < k) {
                return;
            }

            currentState.set(STATE_WRITE_ONLY);
            broadcastStateChange(STATE_WRITE_ONLY);
            scheduler.awaitIdle();

            committer.commitAndUpdate((int) writeBackRound.getAndIncrement());

            currentState.set(STATE_READ_ONLY);
            broadcastStateChange(STATE_READ_ONLY);
        } catch (Exception e) {
            logger.error("Write-only transition failed", e);
            currentState.set(STATE_READ_ONLY);
            broadcastStateChange(STATE_READ_ONLY);
        } finally {
            stateLock.writeLock().unlock();
        }
    }

    private void onClientRequest(int clientId, ClientRequestMessage req) {
        stateLock.readLock().lock();
        try {
            if (currentState.get() == STATE_WRITE_ONLY) {
                sendStateChangeNotice(clientId, STATE_WRITE_ONLY);
                return;
            }
            scheduler.dispatch(clientId, req);
        } finally {
            stateLock.readLock().unlock();
        }
    }

    private void broadcastStateChange(int state) {
        for (int clientId : knownClientIds) {
            sendStateChangeNotice(clientId, state);
        }
    }

    private void sendStateChangeNotice(int clientId, int state) {
        if (!communicationSystem.sessionExists(clientId)) {
            knownClientIds.remove(clientId);
            return;
        }
        StateChangeNoticeMessage notice = new StateChangeNoticeMessage(state);
        communicationSystem.sendMessage(clientId, new Message(proxyId, MessageTypes.STATE_CHANGE_NOTICE, notice.toBytes()));
    }

    private void startReporter() {
        ScheduledExecutorService reporter = Executors.newSingleThreadScheduledExecutor();
        reporter.scheduleAtFixedRate(() -> {
            double avgLatencyMs = scheduler.getAndResetAvgLatencyMs();
            long completed = scheduler.getAndResetCompletedCount();
            double throughput = completed / 2.0;
            double serverRoundTripMs = scheduler.getAndResetServerRoundTripMs();
            double writeBackLatencyMs = committer.getAndResetWriteBackLatencyMs();
            double bufferHitRate = scheduler.getAndResetBufferHitRate();
            double subtreeHitRate = scheduler.getAndResetSubtreeHitRate();
            double contentionLockWaitUs = scheduler.getAndResetContentionLockWaitUs();

            String stateString = currentState.get() == STATE_READ_ONLY ? "READ_ONLY" : "WRITE_ONLY";
            logger.info("M-state: {}", stateString);
            logger.info("M-throughput: {}", throughput);
            logger.info("M-avgLatencyMs: {}", avgLatencyMs);
            logger.info("M-serverRoundTripMs: {}", serverRoundTripMs);
            logger.info("M-writeBackLatencyMs: {}", writeBackLatencyMs);
            logger.info("M-opcaBufferSize: {}", opcaBuffer.size());
            logger.info("M-opcaMapEntries: {}", opcaMap.totalEntries());
            logger.info("M-subtreeHitRate: {}", subtreeHitRate);
            logger.info("M-bufferHitRate: {}", bufferHitRate);
            logger.info("M-accessedPathNumber: {}", localSubtree.getAccessedPathNumber());
            logger.info("M-contentionLockWaitUs: {}", contentionLockWaitUs);
            logger.info("M-subtreeSize: {}", localSubtree.size());
        }, 2, 2, TimeUnit.SECONDS);
    }
}
