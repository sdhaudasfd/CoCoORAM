package taostore.client;

import taostore.messages.ClientRequestMessage;
import taostore.messages.ClientResponseMessage;

import java.util.concurrent.atomic.AtomicLong;

public class TaoStoreClient {
    private final ProxyServiceProxy proxyServiceProxy;
    private final AtomicLong requestCounter;

    public TaoStoreClient(int clientId, String proxyIp, int proxyPort) {
        this.proxyServiceProxy = new ProxyServiceProxy(clientId, 0, proxyIp, proxyPort);
        this.requestCounter = new AtomicLong(1);
    }

    public byte[] readMemory(int address) {
        long requestId = requestCounter.getAndIncrement();
        ClientRequestMessage request = new ClientRequestMessage(requestId, ClientRequestMessage.READ, address, null);
        ClientResponseMessage response = proxyServiceProxy.sendRequest(request);
        return response.getValue();
    }

    public byte[] writeMemory(int address, byte[] content) {
        long requestId = requestCounter.getAndIncrement();
        ClientRequestMessage request = new ClientRequestMessage(requestId, ClientRequestMessage.WRITE, address, content);
        ClientResponseMessage response = proxyServiceProxy.sendRequest(request);
        return response.getValue();
    }

    public void drain() {
        long requestId = requestCounter.getAndIncrement();
        proxyServiceProxy.drain(requestId);
    }

    public void close() {
        proxyServiceProxy.close();
    }
}
