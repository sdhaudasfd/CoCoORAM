package oram.messages;

import oram.utils.ORAMUtils;
import oram.utils.RawCustomExternalizable;

public class Round3UpdateCurRequest implements RawCustomExternalizable {
    private int seq;
    private byte[] encryptedSlotCur;
    private byte[] encryptedMpCur;

    public Round3UpdateCurRequest() {
        this.encryptedSlotCur = new byte[0];
        this.encryptedMpCur = new byte[0];
    }

    public Round3UpdateCurRequest(int seq, byte[] encryptedSlotCur, byte[] encryptedMpCur) {
        this.seq = seq;
        this.encryptedSlotCur = encryptedSlotCur == null ? new byte[0] : encryptedSlotCur;
        this.encryptedMpCur = encryptedMpCur == null ? new byte[0] : encryptedMpCur;
    }

    public int getSeq() {
        return seq;
    }

    public byte[] getEncryptedSlotCur() {
        return encryptedSlotCur;
    }

    public byte[] getEncryptedMpCur() {
        return encryptedMpCur;
    }

    @Override
    public int getSerializedSize() {
        return Integer.BYTES * 3 + encryptedSlotCur.length + encryptedMpCur.length;
    }

    @Override
    public int writeExternal(byte[] output, int startOffset) {
        int offset = startOffset;

        ORAMUtils.serializeInteger(seq, output, offset);
        offset += Integer.BYTES;

        ORAMUtils.serializeInteger(encryptedSlotCur.length, output, offset);
        offset += Integer.BYTES;
        System.arraycopy(encryptedSlotCur, 0, output, offset, encryptedSlotCur.length);
        offset += encryptedSlotCur.length;

        ORAMUtils.serializeInteger(encryptedMpCur.length, output, offset);
        offset += Integer.BYTES;
        System.arraycopy(encryptedMpCur, 0, output, offset, encryptedMpCur.length);
        offset += encryptedMpCur.length;

        return offset;
    }

    @Override
    public int readExternal(byte[] input, int startOffset) {
        int offset = startOffset;

        seq = ORAMUtils.deserializeInteger(input, offset);
        offset += Integer.BYTES;

        int slotLen = ORAMUtils.deserializeInteger(input, offset);
        offset += Integer.BYTES;
        encryptedSlotCur = new byte[slotLen];
        System.arraycopy(input, offset, encryptedSlotCur, 0, slotLen);
        offset += slotLen;

        int mpLen = ORAMUtils.deserializeInteger(input, offset);
        offset += Integer.BYTES;
        encryptedMpCur = new byte[mpLen];
        System.arraycopy(input, offset, encryptedMpCur, 0, mpLen);
        offset += mpLen;

        return offset;
    }
}
