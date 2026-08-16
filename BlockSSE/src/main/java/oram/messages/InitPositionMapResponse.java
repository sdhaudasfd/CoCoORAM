package oram.messages;

import oram.structure.EncryptedGlobalMapEntry;
import oram.utils.ORAMUtils;
import oram.utils.RawCustomExternalizable;

public class InitPositionMapResponse implements RawCustomExternalizable {
    private EncryptedGlobalMapEntry[] encryptedPositionMap;

    public InitPositionMapResponse() {
        this.encryptedPositionMap = new EncryptedGlobalMapEntry[0];
    }

    public InitPositionMapResponse(EncryptedGlobalMapEntry[] encryptedPositionMap) {
        this.encryptedPositionMap = encryptedPositionMap;
    }

    public EncryptedGlobalMapEntry[] getEncryptedPositionMap() {
        return encryptedPositionMap;
    }

    @Override
    public int getSerializedSize() {
        int size = Integer.BYTES;
        for (EncryptedGlobalMapEntry entry : encryptedPositionMap) {
            size += entry.getSerializedSize();
        }
        return size;
    }

    @Override
    public int writeExternal(byte[] output, int startOffset) {
        int offset = startOffset;
        ORAMUtils.serializeInteger(encryptedPositionMap.length, output, offset);
        offset += Integer.BYTES;
        for (EncryptedGlobalMapEntry entry : encryptedPositionMap) {
            offset = entry.writeExternal(output, offset);
        }
        return offset;
    }

    @Override
    public int readExternal(byte[] input, int startOffset) {
        int offset = startOffset;
        int length = ORAMUtils.deserializeInteger(input, offset);
        offset += Integer.BYTES;
        encryptedPositionMap = new EncryptedGlobalMapEntry[length];
        for (int i = 0; i < length; i++) {
            EncryptedGlobalMapEntry entry = new EncryptedGlobalMapEntry();
            offset = entry.readExternal(input, offset);
            encryptedPositionMap[i] = entry;
        }
        return offset;
    }
}
