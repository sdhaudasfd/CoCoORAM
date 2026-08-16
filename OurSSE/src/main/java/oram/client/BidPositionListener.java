package oram.client;

public interface BidPositionListener {
    void onPositionUpdated(int bid, int pid);
}
