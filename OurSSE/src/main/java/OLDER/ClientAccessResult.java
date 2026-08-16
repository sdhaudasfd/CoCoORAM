package oram.client;

public class ClientAccessResult {
    private final byte[] dataPrev;
    private final byte[] dataCur;
    private final DelayedRequest delayedRequest;

    public ClientAccessResult(byte[] dataPrev, byte[] dataCur, DelayedRequest delayedRequest) {
        this.dataPrev = dataPrev;
        this.dataCur = dataCur;
        this.delayedRequest = delayedRequest;
    }

    public byte[] getDataPrev() {
        return dataPrev;
    }

    public byte[] getDataCur() {
        return dataCur;
    }

    public DelayedRequest getDelayedRequest() {
        return delayedRequest;
    }
}
