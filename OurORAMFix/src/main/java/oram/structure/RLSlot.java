package oram.structure;

public class RLSlot extends ORAMBlock {
    public RLSlot() {
        super();
    }

    public RLSlot(int bid, byte[] data, int pid, int seq, boolean dummy) {
        super(bid, data, pid, seq, dummy);
    }

    public static RLSlot dummy(int blockSize) {
        return new RLSlot(DUMMY_BID, new byte[blockSize], DUMMY_PID, DUMMY_SEQ, true);
    }

    @Override
    public String toString() {
        return "RLSlot{" +
                "bid=" + getBid() +
                ", pid=" + getPid() +
                ", seq=" + getSeq() +
                ", dummy=" + isDummy() +
                '}';
    }
}
