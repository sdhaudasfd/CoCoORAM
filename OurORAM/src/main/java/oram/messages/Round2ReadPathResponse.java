package oram.messages;

import oram.structure.EncryptedORAMPath;
import oram.utils.RawCustomExternalizable;

public class Round2ReadPathResponse implements RawCustomExternalizable {
    private EncryptedORAMPath encryptedPath;

    public Round2ReadPathResponse() {
        this.encryptedPath = new EncryptedORAMPath();
    }

    public Round2ReadPathResponse(EncryptedORAMPath encryptedPath) {
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
        encryptedPath = new EncryptedORAMPath();
        return encryptedPath.readExternal(input, startOffset);
    }
}
