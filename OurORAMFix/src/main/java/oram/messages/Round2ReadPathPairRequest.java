package oram.messages;

import oram.utils.ORAMUtils;
import oram.utils.RawCustomExternalizable;

public class Round2ReadPathPairRequest implements RawCustomExternalizable {
    private int firstPid;
    private int secondPid;

    public Round2ReadPathPairRequest() {
    }

    public Round2ReadPathPairRequest(int firstPid, int secondPid) {
        this.firstPid = firstPid;
        this.secondPid = secondPid;
    }

    public int getFirstPid() {
        return firstPid;
    }

    public int getSecondPid() {
        return secondPid;
    }

    @Override
    public int getSerializedSize() {
        return Integer.BYTES * 2;
    }

    @Override
    public int writeExternal(byte[] output, int startOffset) {
        int offset = startOffset;
        ORAMUtils.serializeInteger(firstPid, output, offset);
        offset += Integer.BYTES;
        ORAMUtils.serializeInteger(secondPid, output, offset);
        return offset + Integer.BYTES;
    }

    @Override
    public int readExternal(byte[] input, int startOffset) {
        int offset = startOffset;
        firstPid = ORAMUtils.deserializeInteger(input, offset);
        offset += Integer.BYTES;
        secondPid = ORAMUtils.deserializeInteger(input, offset);
        return offset + Integer.BYTES;
    }
}
