package oram.messages;

import oram.structure.EncryptedORAMPath;
import oram.utils.RawCustomExternalizable;

public class DummyTurnRequest implements RawCustomExternalizable {
    private EncryptedORAMPath encryptedPath;

    public DummyTurnRequest() {
        this.encryptedPath = new EncryptedORAMPath();
    }

    public DummyTurnRequest(EncryptedORAMPath encryptedPath) {
        this.encryptedPath = encryptedPath;
    }

    public EncryptedORAMPath getEncryptedPath() {
        return encryptedPath;
    }

    @Override
    public int getSerializedSize() {
        return encryptedPath.getSerializedSize();
    }

    @Override
    public int writeExternal(byte[] output, int startOffset) {
        return encryptedPath.writeExternal(output, startOffset);
    }

    @Override
    public int readExternal(byte[] input, int startOffset) {
        return encryptedPath.readExternal(input, startOffset);
    }
}
