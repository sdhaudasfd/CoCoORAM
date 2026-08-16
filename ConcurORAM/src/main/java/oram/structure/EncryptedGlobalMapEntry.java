package oram.structure;

import oram.utils.ORAMUtils;
import oram.utils.RawCustomExternalizable;

public class EncryptedGlobalMapEntry implements RawCustomExternalizable {
    private byte[] ciphertext;

    public EncryptedGlobalMapEntry() {
        this.ciphertext = new byte[0];
    }

    public EncryptedGlobalMapEntry(byte[] ciphertext) {
        this.ciphertext = ciphertext == null ? new byte[0] : ciphertext;
    }

    public byte[] getCiphertext() {
        return ciphertext;
    }

    @Override
    public int getSerializedSize() {
        return Integer.BYTES + ciphertext.length;
    }

    @Override
    public int writeExternal(byte[] output, int startOffset) {
        int offset = startOffset;
        ORAMUtils.serializeInteger(ciphertext.length, output, offset);
        offset += Integer.BYTES;
        System.arraycopy(ciphertext, 0, output, offset, ciphertext.length);
        offset += ciphertext.length;
        return offset;
    }

    @Override
    public int readExternal(byte[] input, int startOffset) {
        int offset = startOffset;
        int length = ORAMUtils.deserializeInteger(input, offset);
        offset += Integer.BYTES;
        ciphertext = new byte[length];
        System.arraycopy(input, offset, ciphertext, 0, length);
        offset += length;
        return offset;
    }
}
