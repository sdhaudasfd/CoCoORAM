package oram.messages;

import oram.utils.ORAMUtils;
import oram.utils.RawCustomExternalizable;

public class Round1QueryRequest implements RawCustomExternalizable {
    private byte[] encryptedRequestItem;

    public Round1QueryRequest() {
        this.encryptedRequestItem = new byte[0];
    }

    public Round1QueryRequest(byte[] encryptedRequestItem) {
        this.encryptedRequestItem = encryptedRequestItem == null ? new byte[0] : encryptedRequestItem;
    }

    public byte[] getEncryptedRequestItem() {
        return encryptedRequestItem;
    }

    @Override
    public int getSerializedSize() {
        return Integer.BYTES + encryptedRequestItem.length;
    }

    @Override
    public int writeExternal(byte[] output, int startOffset) {
        int offset = startOffset;

        ORAMUtils.serializeInteger(encryptedRequestItem.length, output, offset);
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

        encryptedRequestItem = new byte[length];
        System.arraycopy(input, offset, encryptedRequestItem, 0, length);
        offset += length;

        return offset;
    }
}
