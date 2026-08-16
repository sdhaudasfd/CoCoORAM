package taostore.proxy;

import comunication.Message;
import comunication.MessageProcessor;
import comunication.server.ServerCommunicationSystem;
import oram.common.ORAMContext;
import oram.common.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import taostore.messages.DrainRequestMessage;
import taostore.messages.DrainResponseMessage;
import taostore.messages.InitOramRequestMessage;
import taostore.messages.InitOramResponseMessage;
import taostore.messages.MessageTypes;
import taostore.messages.ReadPathResponseMessage;
import taostore.messages.WriteBackAckMessage;

import java.io.IOException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class TrustedProxy {
    private static final Logger logger = LoggerFactory.getLogger("taostore.proxy");
    private static final boolean REPORT_RUNTIME_METRICS =
            Boolean.parseBoolean(System.getProperty("taostore.reportMetrics", "false"));

    public static void main(String[] args) {
        if (args.length != 9) {
            System.out.println("Usage: taostore.proxy.TrustedProxy <proxyIp> <proxyPort> <serverIp> <serverPort> <treeHeight> <bucketSize> <blockSize> <writeBackThreshold> <processId>");
            System.exit(-1);
        }

        String proxyIp = args[0];
        int proxyPort = Integer.parseInt(args[1]);
        String serverIp = args[2];
        int serverPort = Integer.parseInt(args[3]);
        int treeHeight = Integer.parseInt(args[4]);
        int bucketSize = Integer.parseInt(args[5]);
        int blockSize = Integer.parseInt(args[6]);
        int writeBackThreshold = Integer.parseInt(args[7]);
        int processId = Integer.parseInt(args[8]);

        int serverId = 0;

        try {
            ServerCommunicationSystem proxyComm = new ServerCommunicationSystem(
                    processId,
                    proxyIp,
                    proxyPort,
                    16,
                    100_000_000
            );

            CompletableFuture<InitOramResponseMessage> initFuture = new CompletableFuture<>();
            MessageProcessor initResponseProcessor = new MessageProcessor(MessageTypes.INIT_ORAM_RESPONSE) {
                @Override
                public void deliverMessage(Message message) {
                    initFuture.complete(InitOramResponseMessage.fromBytes(message.getSerializedMessage()));
                }
            };
            initResponseProcessor.start();
            proxyComm.registerMessageListener(MessageTypes.INIT_ORAM_RESPONSE, initResponseProcessor);

            proxyComm.connectTo(serverId, serverIp, serverPort);
            InitOramRequestMessage initRequest = new InitOramRequestMessage(treeHeight, bucketSize, blockSize);
            final int initAttempts = 20;
            final int initTimeoutSeconds = 10;
            boolean initialized = false;

            for (int attempt = 1; attempt <= initAttempts; attempt++) {
                proxyComm.sendMessage(serverId, new Message(processId, MessageTypes.INIT_ORAM_REQUEST,
                        initRequest.toBytes()));
                try {
                    InitOramResponseMessage initResp = initFuture.get(initTimeoutSeconds, TimeUnit.SECONDS);
                    if (initResp.getStatus() != Status.SUCCESS) {
                        throw new IllegalStateException("Failed to initialize storage server ORAM");
                    }
                    initialized = true;
                    break;
                } catch (TimeoutException timeoutException) {
                    if (attempt == initAttempts) {
                        throw new TimeoutException(
                                "Timed out waiting for INIT_ORAM_RESPONSE from storage server " +
                                        serverIp + ":" + serverPort +
                                        " after " + initAttempts + " attempts"
                        );
                    }
                    logger.warn("INIT_ORAM_RESPONSE timeout (attempt {}/{}), retrying...", attempt, initAttempts);
                }
            }

            if (!initialized) {
                throw new TimeoutException("Failed to initialize storage server ORAM at " + serverIp + ":" + serverPort);
            }

            ORAMContext context = new ORAMContext(treeHeight, bucketSize, blockSize);
            Processor processor = new Processor(processId, serverId, context, writeBackThreshold, proxyComm);
            Sequencer sequencer = new Sequencer(processor, proxyComm, processId);
            processor.setSequencer(sequencer);

            ProxyCommunicationHandler clientHandler = new ProxyCommunicationHandler(sequencer);
            clientHandler.start();
            proxyComm.registerMessageListener(MessageTypes.CLIENT_REQUEST, clientHandler);

            MessageProcessor drainRequestHandler = new MessageProcessor(MessageTypes.DRAIN_REQUEST) {
                @Override
                public void deliverMessage(comunication.Message message) {
                    DrainRequestMessage request = DrainRequestMessage.fromBytes(message.getSerializedMessage());
                    try {
                        processor.drain();
                    } catch (Exception e) {
                        logger.error("Failed to drain TaoStore proxy", e);
                    }
                    DrainResponseMessage response = new DrainResponseMessage(request.getRequestId());
                    proxyComm.sendMessage(message.getSender(),
                            new comunication.Message(processId, MessageTypes.DRAIN_RESPONSE, response.toBytes()));
                }
            };
            drainRequestHandler.start();
            proxyComm.registerMessageListener(MessageTypes.DRAIN_REQUEST, drainRequestHandler);

            MessageProcessor readPathResponseHandler = new MessageProcessor(MessageTypes.READ_PATH_RESPONSE) {
                @Override
                public void deliverMessage(Message message) {
                    processor.onReadPathResponse(ReadPathResponseMessage.fromBytes(message.getSerializedMessage()));
                }
            };
            readPathResponseHandler.start();
            proxyComm.registerMessageListener(MessageTypes.READ_PATH_RESPONSE, readPathResponseHandler);

            MessageProcessor writeBackAckHandler = new MessageProcessor(MessageTypes.WRITE_BACK_ACK) {
                @Override
                public void deliverMessage(Message message) {
                    processor.onWriteBackAck(WriteBackAckMessage.fromBytes(message.getSerializedMessage()));
                }
            };
            writeBackAckHandler.start();
            proxyComm.registerMessageListener(MessageTypes.WRITE_BACK_ACK, writeBackAckHandler);

            if (REPORT_RUNTIME_METRICS) {
                startReporter(processor, sequencer);
            }
            logger.info("Proxy ready");

            while (true) {
                Thread.sleep(60_000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Proxy interrupted", e);
        } catch (IOException | CertificateException | UnrecoverableKeyException | NoSuchAlgorithmException
                 | KeyStoreException e) {
            throw new RuntimeException("Failed to start proxy networking", e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to start trusted proxy", e);
        }
    }

    private static void startReporter(Processor processor, Sequencer sequencer) {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(() -> {
            double avgLatency = sequencer.getAndResetAvgLatencyMs();
            long answered = sequencer.getAndResetRepliedCount();
            double throughput = answered / 2.0;
            double serverRoundTrip = processor.getAndResetServerRoundTripMs();

            logger.info("M-activeRequests: {}", processor.getActiveRequests());
            logger.info("M-subtreeSize: {}", processor.getSubtreeSize());
            logger.info("M-stashSize: {}", processor.getStashSize());
            logger.info("M-writeQueueSize: {}", processor.getWriteQueueSize());
            logger.info("M-throughput: {}", throughput);
            logger.info("M-avgLatencyMs: {}", avgLatency);
            logger.info("M-serverRoundTripMs: {}", serverRoundTrip);
        }, 2, 2, TimeUnit.SECONDS);
    }
}
