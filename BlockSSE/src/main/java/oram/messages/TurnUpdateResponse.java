package oram.messages;

import oram.structure.EncryptedMapUpdate;
import oram.utils.RawCustomExternalizable;

public class TurnUpdateResponse implements RawCustomExternalizable {
    private EncryptedMapUpdate encryptedMapUpdate;

    public TurnUpdateResponse() {
        this.encryptedMapUpdate = new EncryptedMapUpdate();
    }

    public TurnUpdateResponse(EncryptedMapUpdate encryptedMapUpdate) {
        this.encryptedMapUpdate = encryptedMapUpdate;
    }

    public EncryptedMapUpdate getEncryptedMapUpdate() {
        return encryptedMapUpdate;
    }

    @Override
    public int getSerializedSize() {
        return encryptedMapUpdate.getSerializedSize();
    }

    @Override
    public int writeExternal(byte[] output, int startOffset) {
        return encryptedMapUpdate.writeExternal(output, startOffset);
    }

    @Override
    public int readExternal(byte[] input, int startOffset) {
        return encryptedMapUpdate.readExternal(input, startOffset);
    }
}
