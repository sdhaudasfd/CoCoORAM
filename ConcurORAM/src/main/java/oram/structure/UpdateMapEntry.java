package oram.structure;

import oram.utils.ORAMUtils;
import oram.utils.RawCustomExternalizable;

public class UpdateMapEntry implements RawCustomExternalizable {
    public static final int DUMMY_BID = -1;
    public static final int DUMMY_PID = -1;
    public static final int DUMMY_LEVEL = -1;
    public static final int DUMMY_SLOT = -1;
    public static final int DUMMY_SEQ = -1;

    private int bid;
    private int pid;
    private int level;
    private int slot;
    private int seq;
    private boolean dummy;

    public UpdateMapEntry() {
        this.bid = DUMMY_BID;
        this.pid = DUMMY_PID;
        this.level = DUMMY_LEVEL;
        this.slot = DUMMY_SLOT;
        this.seq = DUMMY_SEQ;
        this.dummy = true;
    }

    public UpdateMapEntry(int bid, int pid, int level, int slot, int seq, boolean dummy) {
        this.bid = bid;
        this.pid = pid;
        this.level = level;
        this.slot = slot;
        this.seq = seq;
        this.dummy = dummy;
    }

    public static UpdateMapEntry dummy() {
        return new UpdateMapEntry(DUMMY_BID, DUMMY_PID, DUMMY_LEVEL, DUMMY_SLOT, DUMMY_SEQ, true);
    }

    public int getBid() {
        return bid;
    }

    public int getPid() {
        return pid;
    }

    public int getLevel() {
        return level;
    }

    public int getSlot() {
        return slot;
    }

    public int getSeq() {
        return seq;
    }

    public boolean isDummy() {
        return dummy;
    }

    @Override
    public int getSerializedSize() {
        return Integer.BYTES * 6;
    }

    @Override
    public int writeExternal(byte[] output, int startOffset) {
        int offset = startOffset;

        ORAMUtils.serializeInteger(dummy ? 1 : 0, output, offset);
        offset += Integer.BYTES;

        ORAMUtils.serializeInteger(bid, output, offset);
        offset += Integer.BYTES;

        ORAMUtils.serializeInteger(pid, output, offset);
        offset += Integer.BYTES;

        ORAMUtils.serializeInteger(level, output, offset);
        offset += Integer.BYTES;

        ORAMUtils.serializeInteger(slot, output, offset);
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

        pid = ORAMUtils.deserializeInteger(input, offset);
        offset += Integer.BYTES;

        level = ORAMUtils.deserializeInteger(input, offset);
        offset += Integer.BYTES;

        slot = ORAMUtils.deserializeInteger(input, offset);
        offset += Integer.BYTES;

        seq = ORAMUtils.deserializeInteger(input, offset);
        offset += Integer.BYTES;

        return offset;
    }

    @Override
    public String toString() {
        return "UpdateMapEntry{" +
                "bid=" + bid +
                ", pid=" + pid +
                ", level=" + level +
                ", slot=" + slot +
                ", seq=" + seq +
                ", dummy=" + dummy +
                '}';
    }
}
