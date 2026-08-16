package opca.client;

import opca.messages.ClientRequestMessage;
import opca.messages.ClientResponseMessage;

import java.util.concurrent.atomic.AtomicLong;

public class OpcaClient {
    private final OpcaClientProxy proxyServiceProxy;
    private final AtomicLong requestCounter;

    public OpcaClient(int clientId, String proxyIp, int proxyPort) {
        this.proxyServiceProxy = new OpcaClientProxy(clientId, 0, proxyIp, proxyPort);
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
