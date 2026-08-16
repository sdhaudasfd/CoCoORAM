package oram.messages;

import oram.utils.ORAMUtils;
import oram.utils.RawCustomExternalizable;

public class Round3SubmitEvictionResponse implements RawCustomExternalizable {
    private boolean success;

    public Round3SubmitEvictionResponse() {
    }

    public Round3SubmitEvictionResponse(boolean success) {
        this.success = success;
    }

    public boolean isSuccess() {
        return success;
    }

    @Override
    public int getSerializedSize() {
        return Integer.BYTES;
    }

    @Override
    public int writeExternal(byte[] output, int startOffset) {
        ORAMUtils.serializeInteger(success ? 1 : 0, output, startOffset);
        return startOffset + Integer.BYTES;
    }

    @Override
    public int readExternal(byte[] input, int startOffset) {
        success = ORAMUtils.deserializeInteger(input, startOffset) != 0;
        return startOffset + Integer.BYTES;
    }
}
