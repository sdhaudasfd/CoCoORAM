package taostore.client;

import comunication.Message;
import comunication.MessageProcessor;
import comunication.client.ClientCommunicationSystem;
import taostore.messages.ClientRequestMessage;
import taostore.messages.ClientResponseMessage;
import taostore.messages.DrainRequestMessage;
import taostore.messages.DrainResponseMessage;
import taostore.messages.MessageTypes;

import java.io.IOException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class ProxyServiceProxy extends MessageProcessor {
    private final int id;
    private final int proxyPeerId;
    private final ClientCommunicationSystem communicationSystem;
    private final ConcurrentHashMap<Long, CompletableFuture<ClientResponseMessage>> pendingResponses;
    private final ConcurrentHashMap<Long, CompletableFuture<DrainResponseMessage>> pendingDrains;
    private final MessageProcessor drainResponseProcessor;

    public ProxyServiceProxy(int id, int proxyPeerId, String proxyIp, int proxyPort) {
        super(MessageTypes.CLIENT_RESPONSE);
        this.id = id;
        this.proxyPeerId = proxyPeerId;
        this.pendingResponses = new ConcurrentHashMap<>();
        this.pendingDrains = new ConcurrentHashMap<>();

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
            this.communicationSystem.connectTo(proxyPeerId, proxyIp, proxyPort);
        } catch (InterruptedException | IOException | NoSuchAlgorithmException | KeyStoreException
                 | CertificateException | UnrecoverableKeyException e) {
            throw new RuntimeException("Failed to connect to proxy", e);
        }
    }

    public void drain(long requestId) {
        CompletableFuture<DrainResponseMessage> future = new CompletableFuture<>();
        pendingDrains.put(requestId, future);

        DrainRequestMessage request = new DrainRequestMessage(requestId);
        communicationSystem.sendMessage(proxyPeerId, new Message(id, MessageTypes.DRAIN_REQUEST, request.toBytes()));

        try {
            future.get();
        } catch (Exception e) {
            pendingDrains.remove(requestId);
            throw new RuntimeException("Failed waiting drain response from proxy", e);
        }
    }

    public ClientResponseMessage sendRequest(ClientRequestMessage request) {
        CompletableFuture<ClientResponseMessage> future = new CompletableFuture<>();
        pendingResponses.put(request.getRequestId(), future);

        Message m = new Message(id, MessageTypes.CLIENT_REQUEST, request.toBytes());
        communicationSystem.sendMessage(proxyPeerId, m);

        try {
            return future.get();
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

    public void close() {
        communicationSystem.shutdown();
        drainResponseProcessor.interrupt();
        interrupt();
    }
}
