package oram.messages;

import oram.structure.EncryptedGlobalMapEntry;
import oram.utils.ORAMUtils;
import oram.utils.RawCustomExternalizable;

public class InitGbMpResponse implements RawCustomExternalizable {
    private EncryptedGlobalMapEntry[] encryptedGbMp;

    public InitGbMpResponse() {
        this.encryptedGbMp = new EncryptedGlobalMapEntry[0];
    }

    public InitGbMpResponse(EncryptedGlobalMapEntry[] encryptedGbMp) {
        this.encryptedGbMp = copyEncryptedGbMp(encryptedGbMp);
    }

    public EncryptedGlobalMapEntry[] getEncryptedGbMp() {
        return copyEncryptedGbMp(encryptedGbMp);
    }

    @Override
    public int getSerializedSize() {
        int size = Integer.BYTES;
        for (EncryptedGlobalMapEntry entry : encryptedGbMp) {
            size += 1;
            if (entry != null) {
                size += entry.getSerializedSize();
            }
        }
        return size;
    }

    @Override
    public int writeExternal(byte[] output, int startOffset) {
        int offset = startOffset;

        ORAMUtils.serializeInteger(encryptedGbMp.length, output, offset);
        offset += Integer.BYTES;

        for (EncryptedGlobalMapEntry entry : encryptedGbMp) {
            output[offset++] = (byte) (entry == null ? 0 : 1);
            if (entry != null) {
                offset = entry.writeExternal(output, offset);
            }
        }

        return offset;
    }

    @Override
    public int readExternal(byte[] input, int startOffset) {
        int offset = startOffset;

        int gbMpSize = ORAMUtils.deserializeInteger(input, offset);
        offset += Integer.BYTES;

        encryptedGbMp = new EncryptedGlobalMapEntry[gbMpSize];
        for (int i = 0; i < gbMpSize; i++) {
            int present = input[offset++] & 0xFF;
            if (present == 0) {
                continue;
            }
            EncryptedGlobalMapEntry entry = new EncryptedGlobalMapEntry();
            offset = entry.readExternal(input, offset);
            encryptedGbMp[i] = entry;
        }

        return offset;
    }

    private static EncryptedGlobalMapEntry[] copyEncryptedGbMp(EncryptedGlobalMapEntry[] source) {
        EncryptedGlobalMapEntry[] copy = new EncryptedGlobalMapEntry[source.length];

        for (int i = 0; i < source.length; i++) {
            EncryptedGlobalMapEntry entry = source[i];
            if (entry == null) {
                continue;
            }
            byte[] ciphertext = entry.getCiphertext();
            byte[] ciphertextCopy = new byte[ciphertext.length];
            System.arraycopy(ciphertext, 0, ciphertextCopy, 0, ciphertext.length);
            copy[i] = new EncryptedGlobalMapEntry(ciphertextCopy);
        }

        return copy;
    }
}
