package oram.client.module;

public class ClientAccessResult {
    private final byte[] dataPrev;
    private final byte[] dataCur;
    private final DelayedRequest delayedRequest;
    private final int updatedPid;

    public ClientAccessResult(byte[] dataPrev, byte[] dataCur, DelayedRequest delayedRequest) {
        this(dataPrev, dataCur, delayedRequest, -1);
    }

    public ClientAccessResult(byte[] dataPrev,
                              byte[] dataCur,
                              DelayedRequest delayedRequest,
                              int updatedPid) {
        this.dataPrev = dataPrev;
        this.dataCur = dataCur;
        this.delayedRequest = delayedRequest;
        this.updatedPid = updatedPid;
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

    public int getUpdatedPid() {
        return updatedPid;
    }
}
