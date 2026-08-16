package oram.messages;

import oram.utils.ORAMUtils;
import oram.utils.RawCustomExternalizable;

public class RegisterAccessResponse implements RawCustomExternalizable {
    private int seq;

    public RegisterAccessResponse() {
    }

    public RegisterAccessResponse(int seq) {
        this.seq = seq;
    }

    public int getSeq() {
        return seq;
    }

    @Override
    public int getSerializedSize() {
        return Integer.BYTES;
    }

    @Override
    public int writeExternal(byte[] output, int startOffset) {
        ORAMUtils.serializeInteger(seq, output, startOffset);
        return startOffset + Integer.BYTES;
    }

    @Override
    public int readExternal(byte[] input, int startOffset) {
        seq = ORAMUtils.deserializeInteger(input, startOffset);
        return startOffset + Integer.BYTES;
    }
}
