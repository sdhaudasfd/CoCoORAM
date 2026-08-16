package oram.messages;

import oram.utils.RawCustomExternalizable;

public class InitPositionMapRequest implements RawCustomExternalizable {
    @Override
    public int getSerializedSize() {
        return 0;
    }

    @Override
    public int writeExternal(byte[] output, int startOffset) {
        return startOffset;
    }

    @Override
    public int readExternal(byte[] input, int startOffset) {
        return startOffset;
    }
}
