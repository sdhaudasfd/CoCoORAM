package oram.messages;

import oram.utils.ORAMUtils;
import oram.utils.RawCustomExternalizable;

public class Round3UpdateCurResponse implements RawCustomExternalizable {
    private boolean timestepAdvanced;

    public Round3UpdateCurResponse() {
    }

    public Round3UpdateCurResponse(boolean timestepAdvanced) {
        this.timestepAdvanced = timestepAdvanced;
    }

    public boolean isTimestepAdvanced() {
        return timestepAdvanced;
    }

    @Override
    public int getSerializedSize() {
        return Integer.BYTES;
    }

    @Override
    public int writeExternal(byte[] output, int startOffset) {
        ORAMUtils.serializeInteger(timestepAdvanced ? 1 : 0, output, startOffset);
        return startOffset + Integer.BYTES;
    }

    @Override
    public int readExternal(byte[] input, int startOffset) {
        timestepAdvanced = ORAMUtils.deserializeInteger(input, startOffset) != 0;
        return startOffset + Integer.BYTES;
    }
}
