package oram.messages;

import oram.utils.ORAMUtils;
import oram.utils.RawCustomExternalizable;

public class Round3CommitEvictionRequest implements RawCustomExternalizable {
    private int payloadKey;

    public Round3CommitEvictionRequest() {
        this.payloadKey = 0;
    }

    public Round3CommitEvictionRequest(int payloadKey) {
        this.payloadKey = payloadKey;
    }

    public int getPayloadKey() {
        return payloadKey;
    }

    @Override
    public int getSerializedSize() {
        return Integer.BYTES;
    }

    @Override
    public int writeExternal(byte[] output, int startOffset) {
        ORAMUtils.serializeInteger(payloadKey, output, startOffset);
        return startOffset + Integer.BYTES;
    }

    @Override
    public int readExternal(byte[] input, int startOffset) {
        payloadKey = ORAMUtils.deserializeInteger(input, startOffset);
        return startOffset + Integer.BYTES;
    }
}
