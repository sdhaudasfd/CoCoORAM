package oram.client;

public class ExtractCurResult {
    private final byte[] dataCur;
    private final DelayedRequest delayedRequest;

    public ExtractCurResult(byte[] dataCur, DelayedRequest delayedRequest) {
        this.dataCur = dataCur;
        this.delayedRequest = delayedRequest;
    }

    public byte[] getDataCur() {
        return dataCur;
    }

    public DelayedRequest getDelayedRequest() {
        return delayedRequest;
    }
}
