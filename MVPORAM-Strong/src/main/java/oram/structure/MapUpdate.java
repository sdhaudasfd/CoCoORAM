package oram.structure;

import oram.utils.ORAMUtils;
import oram.utils.RawCustomExternalizable;

public class MapUpdate implements RawCustomExternalizable {
    private int bid;
    private int pid;
    private int seq;

    public MapUpdate() {
    }

    public MapUpdate(int bid, int pid, int seq) {
        this.bid = bid;
        this.pid = pid;
        this.seq = seq;
    }

    public int getBid() {
        return bid;
    }

    public int getPid() {
        return pid;
    }

    public int getSeq() {
        return seq;
    }

    @Override
    public int getSerializedSize() {
        return 3 * Integer.BYTES;
    }

    @Override
    public int writeExternal(byte[] output, int startOffset) {
        int offset = startOffset;
        ORAMUtils.serializeInteger(bid, output, offset);
        offset += Integer.BYTES;
        ORAMUtils.serializeInteger(pid, output, offset);
        offset += Integer.BYTES;
        ORAMUtils.serializeInteger(seq, output, offset);
        return offset + Integer.BYTES;
    }

    @Override
    public int readExternal(byte[] input, int startOffset) {
        int offset = startOffset;
        bid = ORAMUtils.deserializeInteger(input, offset);
        offset += Integer.BYTES;
        pid = ORAMUtils.deserializeInteger(input, offset);
        offset += Integer.BYTES;
        seq = ORAMUtils.deserializeInteger(input, offset);
        return offset + Integer.BYTES;
    }
}
