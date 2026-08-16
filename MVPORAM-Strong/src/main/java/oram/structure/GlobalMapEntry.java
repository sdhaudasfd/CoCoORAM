package oram.structure;

import oram.utils.ORAMUtils;
import oram.utils.RawCustomExternalizable;

public class GlobalMapEntry implements RawCustomExternalizable {
    private int pid;
    private int seq;

    public GlobalMapEntry() {
    }

    public GlobalMapEntry(int pid, int seq) {
        this.pid = pid;
        this.seq = seq;
    }

    public int getPid() {
        return pid;
    }

    public int getSeq() {
        return seq;
    }

    @Override
    public int getSerializedSize() {
        return Integer.BYTES * 2;
    }

    @Override
    public int writeExternal(byte[] output, int startOffset) {
        int offset = startOffset;

        ORAMUtils.serializeInteger(pid, output, offset);
        offset += Integer.BYTES;

        ORAMUtils.serializeInteger(seq, output, offset);
        offset += Integer.BYTES;

        return offset;
    }

    @Override
    public int readExternal(byte[] input, int startOffset) {
        int offset = startOffset;

        pid = ORAMUtils.deserializeInteger(input, offset);
        offset += Integer.BYTES;

        seq = ORAMUtils.deserializeInteger(input, offset);
        offset += Integer.BYTES;

        return offset;
    }

    @Override
    public String toString() {
        return "(pid=" + pid + ", seq=" + seq + ")";
    }
}
