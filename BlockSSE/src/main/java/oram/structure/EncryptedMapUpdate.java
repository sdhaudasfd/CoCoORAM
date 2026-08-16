package oram.structure;

import oram.utils.ORAMUtils;
import oram.utils.RawCustomExternalizable;

public class EncryptedMapUpdate implements RawCustomExternalizable {
    private byte[] ciphertext;

    public EncryptedMapUpdate() {
        this.ciphertext = new byte[0];
    }

    public EncryptedMapUpdate(byte[] ciphertext) {
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
        return offset + ciphertext.length;
    }

    @Override
    public int readExternal(byte[] input, int startOffset) {
        int offset = startOffset;
        int length = ORAMUtils.deserializeInteger(input, offset);
        offset += Integer.BYTES;
        ciphertext = new byte[length];
        System.arraycopy(input, offset, ciphertext, 0, length);
        return offset + length;
    }
}
