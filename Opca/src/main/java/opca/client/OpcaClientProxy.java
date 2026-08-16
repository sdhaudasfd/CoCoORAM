package opca.client;

import comunication.Message;
import comunication.MessageProcessor;
import comunication.client.ClientCommunicationSystem;
import opca.messages.ClientRequestMessage;
import opca.messages.ClientResponseMessage;
import opca.messages.DrainRequestMessage;
import opca.messages.DrainResponseMessage;
import opca.messages.MessageTypes;
import opca.messages.StateChangeNoticeMessage;
import opca.proxy.OpcaProxy;

import java.io.IOException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class OpcaClientProxy extends MessageProcessor {
    private static final long REQUEST_TIMEOUT_SECONDS = 60;
    private static final long PROXY_WAIT_TIMEOUT_SECONDS = 60;

    private final int id;
    private final int proxyPeerId;
    private final ClientCommunicationSystem communicationSystem;
    private final ConcurrentHashMap<Long, CompletableFuture<ClientResponseMessage>> pendingResponses;
    private final ConcurrentHashMap<Long, CompletableFuture<DrainResponseMessage>> pendingDrains;
    private final MessageProcessor stateNoticeProcessor;
    private final MessageProcessor drainResponseProcessor;

    private final Object availabilityLock;
    private volatile boolean proxyAvailable;
    private volatile boolean closed;

    public OpcaClientProxy(int id, int proxyPeerId, String proxyIp, int proxyPort) {
        super(MessageTypes.CLIENT_RESPONSE);
        this.id = id;
        this.proxyPeerId = proxyPeerId;
        this.pendingResponses = new ConcurrentHashMap<>();
        this.pendingDrains = new ConcurrentHashMap<>();
        this.availabilityLock = new Object();
        this.proxyAvailable = true;
        this.closed = false;

        try {
            this.communicationSystem = new ClientCommunicationSystem(id, 1, 100_000_000);
            start();
            communicationSystem.registerMessageListener(MessageTypes.CLIENT_RESPONSE, this);

            this.drainResponseProcessor = new MessageProcessor(MessageTypes.DRAIN_RESPONSE) {
                @Override
                public void deliverMessage(Message message) {
                    DrainResponseMessage response = DrainResponseMessage.fromBytes(message.getSerializedMessage());
                    CompletableFuture<DrainResponseMessage> future = pendingDrains.remove(response.getRequestId());
                    if (future != null) {
                        future.complete(response);
                    }
                }
            };
            drainResponseProcessor.start();
            communicationSystem.registerMessageListener(MessageTypes.DRAIN_RESPONSE, drainResponseProcessor);

            this.stateNoticeProcessor = new MessageProcessor(MessageTypes.STATE_CHANGE_NOTICE) {
                @Override
                public void deliverMessage(Message message) {
                    StateChangeNoticeMessage notice = StateChangeNoticeMessage.fromBytes(message.getSerializedMessage());
                    onStateChange(notice.getState());
                }
            };
            stateNoticeProcessor.start();
            communicationSystem.registerMessageListener(MessageTypes.STATE_CHANGE_NOTICE, stateNoticeProcessor);

            this.communicationSystem.connectTo(proxyPeerId, proxyIp, proxyPort);
        } catch (InterruptedException | IOException | NoSuchAlgorithmException | KeyStoreException
                 | CertificateException | UnrecoverableKeyException e) {
            throw new RuntimeException("Failed to connect to proxy", e);
        }
    }

    public void drain(long requestId) {
        if (closed) {
            throw new IllegalStateException("Client proxy already closed");
        }
        waitForProxy();

        CompletableFuture<DrainResponseMessage> future = new CompletableFuture<>();
        pendingDrains.put(requestId, future);

        DrainRequestMessage request = new DrainRequestMessage(requestId);
        communicationSystem.sendMessage(proxyPeerId, new Message(id, MessageTypes.DRAIN_REQUEST, request.toBytes()));

        try {
            future.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            pendingDrains.remove(requestId);
            throw new RuntimeException("Timeout waiting drain response from proxy", e);
        } catch (Exception e) {
            pendingDrains.remove(requestId);
            throw new RuntimeException("Failed waiting drain response from proxy", e);
        }
    }

    public ClientResponseMessage sendRequest(ClientRequestMessage request) {
        if (closed) {
            throw new IllegalStateException("Client proxy already closed");
        }
        waitForProxy();

        CompletableFuture<ClientResponseMessage> future = new CompletableFuture<>();
        pendingResponses.put(request.getRequestId(), future);

        Message message = new Message(id, MessageTypes.CLIENT_REQUEST, request.toBytes());
        communicationSystem.sendMessage(proxyPeerId, message);

        try {
            return future.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            pendingResponses.remove(request.getRequestId());
            throw new RuntimeException("Timeout waiting response from proxy", e);
        } catch (Exception e) {
            pendingResponses.remove(request.getRequestId());
            throw new RuntimeException("Failed waiting response from proxy", e);
        }
    }

    @Override
    public void deliverMessage(Message message) {
        ClientResponseMessage response = ClientResponseMessage.fromBytes(message.getSerializedMessage());
        CompletableFuture<ClientResponseMessage> future = pendingResponses.remove(response.getRequestId());
        if (future != null) {
            future.complete(response);
        }
    }

    private void onStateChange(int state) {
        synchronized (availabilityLock) {
            if (state == OpcaProxy.STATE_WRITE_ONLY) {
                proxyAvailable = false;
            } else {
                proxyAvailable = true;
                availabilityLock.notifyAll();
            }
        }
    }

    private void waitForProxy() {
        long deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(PROXY_WAIT_TIMEOUT_SECONDS);
        synchronized (availabilityLock) {
            while (!proxyAvailable && !closed) {
                try {
                    availabilityLock.wait(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted while waiting for proxy", e);
                }
                if (!proxyAvailable && System.nanoTime() >= deadlineNs) {
                    throw new RuntimeException("Timeout waiting proxy to return READ_ONLY state");
                }
            }
            if (closed) {
                throw new IllegalStateException("Client proxy closed while waiting for availability");
            }
        }
    }

    public void close() {
        closed = true;
        synchronized (availabilityLock) {
            availabilityLock.notifyAll();
        }

        pendingResponses.forEach((id, future) ->
                future.completeExceptionally(new IllegalStateException("Client proxy closed"))
        );
        pendingResponses.clear();
        pendingDrains.forEach((id, future) ->
                future.completeExceptionally(new IllegalStateException("Client proxy closed"))
        );
        pendingDrains.clear();

        communicationSystem.shutdown();
        stateNoticeProcessor.interrupt();
        drainResponseProcessor.interrupt();
        interrupt();
    }
}
