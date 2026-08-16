package oram.messages;

import oram.structure.EncryptedORAMPath;
import oram.utils.RawCustomExternalizable;

public class ReadPathResponse implements RawCustomExternalizable {
    private EncryptedORAMPath encryptedPath;

    public ReadPathResponse() {
        this.encryptedPath = new EncryptedORAMPath();
    }

    public ReadPathResponse(EncryptedORAMPath encryptedPath) {
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
