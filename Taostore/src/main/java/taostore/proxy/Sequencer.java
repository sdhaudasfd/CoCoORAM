package taostore.proxy;

import comunication.CommunicationSystem;
import comunication.Message;
import taostore.messages.ClientRequestMessage;
import taostore.messages.ClientResponseMessage;
import taostore.messages.MessageTypes;
import taostore.proxy.structure.LogicalRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class Sequencer {
    private final Logger logger = LoggerFactory.getLogger("taostore.proxy");

    private final ArrayDeque<Long> queue;
    private final ConcurrentHashMap<Long, Optional<byte[]>> resultMap;
    private final AtomicLong requestIdCounter;
    private final Processor processor;
    private final CommunicationSystem proxyCommunication;
    private final int proxyId;

    private final ConcurrentHashMap<Long, Integer> requestToClient;
    private final ConcurrentHashMap<Long, Long> internalToClientRequestId;
    private final ConcurrentHashMap<Long, Long> requestStartNs;

    private final ReentrantLock serializerLock;
    private final Condition resultAvailable;
    private final Thread serializerThread;

    private final AtomicLong repliedCount;
    private final AtomicLong latencyNsSum;
    private final AtomicLong intervalRepliedCount;
    private final AtomicLong intervalLatencyNsSum;

    public Sequencer(Processor processor, CommunicationSystem comm, int proxyId) {
        this.queue = new ArrayDeque<>();
        this.resultMap = new ConcurrentHashMap<>();
        this.requestIdCounter = new AtomicLong(1);
        this.processor = processor;
        this.proxyCommunication = comm;
        this.proxyId = proxyId;
        this.requestToClient = new ConcurrentHashMap<>();
        this.internalToClientRequestId = new ConcurrentHashMap<>();
        this.requestStartNs = new ConcurrentHashMap<>();
        this.repliedCount = new AtomicLong(0);
        this.latencyNsSum = new AtomicLong(0);
        this.intervalRepliedCount = new AtomicLong(0);
        this.intervalLatencyNsSum = new AtomicLong(0);

        this.serializerLock = new ReentrantLock();
        this.resultAvailable = serializerLock.newCondition();
        this.serializerThread = new Thread(this::serializationLoop, "taostore-sequencer");
        this.serializerThread.setDaemon(true);
        this.serializerThread.start();
    }

    public void submitRequest(int clientId, ClientRequestMessage req) {
        long internalRequestId = requestIdCounter.getAndIncrement();
        resultMap.put(internalRequestId, Optional.empty());
        requestToClient.put(internalRequestId, clientId);
        internalToClientRequestId.put(internalRequestId, req.getRequestId());
        requestStartNs.put(internalRequestId, System.nanoTime());

        serializerLock.lock();
        try {
            queue.addLast(internalRequestId);
        } finally {
            serializerLock.unlock();
        }

        LogicalRequest lr = new LogicalRequest(internalRequestId, req.getOpType(), req.getBlockAddress(), req.getNewValue());
        processor.submitRequest(lr);
    }

    public void notifyReply(long requestId, byte[] value) {
        Optional<byte[]> entry = resultMap.get(requestId);
        if (entry == null) {
            logger.warn("Ignoring reply for unknown requestId {}", requestId);
            return;
        }
        resultMap.put(requestId, Optional.of(value == null ? new byte[0] : value));
        serializerLock.lock();
        try {
            resultAvailable.signal();
        } finally {
            serializerLock.unlock();
        }
    }

    private void serializationLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            long front;
            Optional<byte[]> entry;
            serializerLock.lock();
            try {
                while (true) {
                    if (queue.isEmpty()) {
                        resultAvailable.await();
                        continue;
                    }
                    front = queue.peekFirst();
                    entry = resultMap.get(front);
                    if (entry != null && entry.isPresent()) {
                        break;
                    }
                    resultAvailable.await();
                }
                queue.pollFirst();
                resultMap.remove(front);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } finally {
                serializerLock.unlock();
            }

            byte[] val = entry.orElse(new byte[0]);
            int clientId = requestToClient.remove(front);
            Long clientRequestId = internalToClientRequestId.remove(front);
            if (clientRequestId == null) {
                logger.warn("Missing client request id mapping for internal request {}", front);
                clientRequestId = front;
            }

            Long startNs = requestStartNs.remove(front);
            if (startNs != null) {
                long latency = System.nanoTime() - startNs;
                latencyNsSum.addAndGet(latency);
                intervalLatencyNsSum.addAndGet(latency);
            }
            repliedCount.incrementAndGet();
            intervalRepliedCount.incrementAndGet();

            ClientResponseMessage resp = new ClientResponseMessage(clientRequestId, val);
            Message m = new Message(proxyId, MessageTypes.CLIENT_RESPONSE, resp.toBytes());
            proxyCommunication.sendMessage(clientId, m);
        }
    }

    public long getAndResetRepliedCount() {
        return intervalRepliedCount.getAndSet(0);
    }

    public double getAndResetAvgLatencyMs() {
        long count = intervalRepliedCount.get();
        if (count == 0) {
            intervalLatencyNsSum.getAndSet(0);
            return -1;
        }
        long sum = intervalLatencyNsSum.getAndSet(0);
        return (sum / (double) count) / 1_000_000.0;
    }
}
