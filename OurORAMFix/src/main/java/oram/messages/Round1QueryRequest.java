package oram.messages;

import oram.utils.ORAMUtils;
import oram.utils.RawCustomExternalizable;

public class Round1QueryRequest implements RawCustomExternalizable {
    private byte[] encryptedRequestItem;
    private int lastAppliedTimestep;
    private int delayedTimestep;

    public Round1QueryRequest() {
        this.encryptedRequestItem = new byte[0];
        this.lastAppliedTimestep = -1;
        this.delayedTimestep = -1;
    }

    public Round1QueryRequest(byte[] encryptedRequestItem, int lastAppliedTimestep, int delayedTimestep) {
        this.encryptedRequestItem = encryptedRequestItem == null ? new byte[0] : encryptedRequestItem;
        this.lastAppliedTimestep = lastAppliedTimestep;
        this.delayedTimestep = delayedTimestep;
    }

    public byte[] getEncryptedRequestItem() {
        return encryptedRequestItem;
    }

    public int getLastAppliedTimestep() {
        return lastAppliedTimestep;
    }

    public int getDelayedTimestep() {
        return delayedTimestep;
    }

    @Override
    public int getSerializedSize() {
        return Integer.BYTES * 3 + encryptedRequestItem.length;
    }

    @Override
    public int writeExternal(byte[] output, int startOffset) {
        int offset = startOffset;

        ORAMUtils.serializeInteger(encryptedRequestItem.length, output, offset);
        offset += Integer.BYTES;

        ORAMUtils.serializeInteger(lastAppliedTimestep, output, offset);
        offset += Integer.BYTES;

        ORAMUtils.serializeInteger(delayedTimestep, output, offset);
        offset += Integer.BYTES;

        System.arraycopy(encryptedRequestItem, 0, output, offset, encryptedRequestItem.length);
        offset += encryptedRequestItem.length;

        return offset;
    }

    @Override
    public int readExternal(byte[] input, int startOffset) {
        int offset = startOffset;

        int length = ORAMUtils.deserializeInteger(input, offset);
        offset += Integer.BYTES;

        lastAppliedTimestep = ORAMUtils.deserializeInteger(input, offset);
        offset += Integer.BYTES;

        delayedTimestep = ORAMUtils.deserializeInteger(input, offset);
        offset += Integer.BYTES;

        encryptedRequestItem = new byte[length];
        System.arraycopy(input, offset, encryptedRequestItem, 0, length);
        offset += length;

        return offset;
    }
}
