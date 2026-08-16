package oram.messages;

import oram.utils.ORAMUtils;
import oram.utils.RawCustomExternalizable;

public class ReadPathRequest implements RawCustomExternalizable {
    private int pid;

    public ReadPathRequest() {
    }

    public ReadPathRequest(int pid) {
        this.pid = pid;
    }

    public int getPid() {
        return pid;
    }

    @Override
    public int getSerializedSize() {
        return Integer.BYTES;
    }

    @Override
    public int writeExternal(byte[] output, int startOffset) {
        ORAMUtils.serializeInteger(pid, output, startOffset);
        return startOffset + Integer.BYTES;
    }

    @Override
    public int readExternal(byte[] input, int startOffset) {
        pid = ORAMUtils.deserializeInteger(input, startOffset);
        return startOffset + Integer.BYTES;
    }
}
