package oram.messages;

import oram.structure.EncryptedMapUpdate;
import oram.structure.EncryptedORAMPath;
import oram.utils.ORAMUtils;
import oram.utils.RawCustomExternalizable;

public class SubmitTurnRequest implements RawCustomExternalizable {
    private int seq;
    private EncryptedORAMPath encryptedPath;
    private EncryptedMapUpdate encryptedMapUpdate;

    public SubmitTurnRequest() {
        this.encryptedPath = new EncryptedORAMPath();
        this.encryptedMapUpdate = new EncryptedMapUpdate();
    }

    public SubmitTurnRequest(int seq,
                             EncryptedORAMPath encryptedPath,
                             EncryptedMapUpdate encryptedMapUpdate) {
        this.seq = seq;
        this.encryptedPath = encryptedPath;
        this.encryptedMapUpdate = encryptedMapUpdate;
    }

    public int getSeq() {
        return seq;
    }

    public EncryptedORAMPath getEncryptedPath() {
        return encryptedPath;
    }

    public EncryptedMapUpdate getEncryptedMapUpdate() {
        return encryptedMapUpdate;
    }

    @Override
    public int getSerializedSize() {
        return Integer.BYTES + encryptedPath.getSerializedSize() + encryptedMapUpdate.getSerializedSize();
    }

    @Override
    public int writeExternal(byte[] output, int startOffset) {
        int offset = startOffset;
        ORAMUtils.serializeInteger(seq, output, offset);
        offset += Integer.BYTES;
        offset = encryptedPath.writeExternal(output, offset);
        return encryptedMapUpdate.writeExternal(output, offset);
    }

    @Override
    public int readExternal(byte[] input, int startOffset) {
        int offset = startOffset;
        seq = ORAMUtils.deserializeInteger(input, offset);
        offset += Integer.BYTES;
        offset = encryptedPath.readExternal(input, offset);
        return encryptedMapUpdate.readExternal(input, offset);
    }
}
