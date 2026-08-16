package oram.structure;

import oram.utils.ORAMUtils;
import oram.utils.RawCustomExternalizable;

public class UpdateMapEntry implements RawCustomExternalizable {
    public static final int DUMMY_BID = -1;
    public static final int DUMMY_RID = -1;
    public static final int DUMMY_PID = -1;
    public static final int DUMMY_SEQ = -1;

    private int bid;
    private int rid;
    private int pid;
    private int seq;
    private boolean dummy;

    public UpdateMapEntry() {
        this.bid = DUMMY_BID;
        this.rid = DUMMY_RID;
        this.pid = DUMMY_PID;
        this.seq = DUMMY_SEQ;
        this.dummy = true;
    }

    public UpdateMapEntry(int bid, int rid, int pid, int seq, boolean dummy) {
        this.bid = bid;
        this.rid = rid;
        this.pid = pid;
        this.seq = seq;
        this.dummy = dummy;
    }

    public static UpdateMapEntry dummy() {
        return new UpdateMapEntry(DUMMY_BID, DUMMY_RID, DUMMY_PID, DUMMY_SEQ, true);
    }

    public int getBid() {
        return bid;
    }

    public int getRid() {
        return rid;
    }

    public int getPid() {
        return pid;
    }

    public int getSeq() {
        return seq;
    }

    public boolean isDummy() {
        return dummy;
    }

    @Override
    public int getSerializedSize() {
        return Integer.BYTES * 5;
    }

    @Override
    public int writeExternal(byte[] output, int startOffset) {
        int offset = startOffset;

        ORAMUtils.serializeInteger(dummy ? 1 : 0, output, offset);
        offset += Integer.BYTES;

        ORAMUtils.serializeInteger(bid, output, offset);
        offset += Integer.BYTES;

        ORAMUtils.serializeInteger(rid, output, offset);
        offset += Integer.BYTES;

        ORAMUtils.serializeInteger(pid, output, offset);
        offset += Integer.BYTES;

        ORAMUtils.serializeInteger(seq, output, offset);
        offset += Integer.BYTES;

        return offset;
    }

    @Override
    public int readExternal(byte[] input, int startOffset) {
        int offset = startOffset;

        dummy = ORAMUtils.deserializeInteger(input, offset) != 0;
        offset += Integer.BYTES;

        bid = ORAMUtils.deserializeInteger(input, offset);
        offset += Integer.BYTES;

        rid = ORAMUtils.deserializeInteger(input, offset);
        offset += Integer.BYTES;

        pid = ORAMUtils.deserializeInteger(input, offset);
        offset += Integer.BYTES;

        seq = ORAMUtils.deserializeInteger(input, offset);
        offset += Integer.BYTES;

        return offset;
    }

    @Override
    public String toString() {
        return "UpdateMapEntry{" +
                "bid=" + bid +
                ", rid=" + rid +
                ", pid=" + pid +
                ", seq=" + seq +
                ", dummy=" + dummy +
                '}';
    }
}
