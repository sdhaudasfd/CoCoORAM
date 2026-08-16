package oram.client.module;

public class DelayedRequest {
    private final int bid;
    private final int seq;

    public DelayedRequest(int bid, int seq) {
        this.bid = bid;
        this.seq = seq;
    }

    public int getBid() {
        return bid;
    }

    public int getSeq() {
        return seq;
    }
}
