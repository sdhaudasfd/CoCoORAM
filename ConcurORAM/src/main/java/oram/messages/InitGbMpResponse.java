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
        this.encryptedGbMp = encryptedGbMp;
    }

    public EncryptedGlobalMapEntry[] getEncryptedGbMp() {
        return encryptedGbMp;
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
}
