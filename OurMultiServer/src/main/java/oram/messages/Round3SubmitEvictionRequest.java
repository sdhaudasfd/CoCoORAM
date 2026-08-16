package oram.messages;

import oram.structure.EncryptedBucketSegment;
import oram.utils.ORAMUtils;
import oram.utils.RawCustomExternalizable;

public class Round3SubmitEvictionRequest implements RawCustomExternalizable {
    private int seq;
    private byte[] encryptedSlotCur;
    private byte[] encryptedMpCur;
    private int pid;
    private EncryptedBucketSegment[] encryptedSegments;

    public Round3SubmitEvictionRequest() {
        this.seq = 0;
        this.encryptedSlotCur = new byte[0];
        this.encryptedMpCur = new byte[0];
        this.pid = 0;
        this.encryptedSegments = new EncryptedBucketSegment[0];
    }

    public Round3SubmitEvictionRequest(int seq,
                                       byte[] encryptedSlotCur,
                                       byte[] encryptedMpCur,
                                       int pid,
                                       EncryptedBucketSegment[] encryptedSegments) {
        this.seq = seq;
        this.encryptedSlotCur = encryptedSlotCur == null ? new byte[0] : encryptedSlotCur;
        this.encryptedMpCur = encryptedMpCur == null ? new byte[0] : encryptedMpCur;
        this.pid = pid;
        this.encryptedSegments = encryptedSegments == null ? new EncryptedBucketSegment[0] : encryptedSegments;
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

    public int getPid() {
        return pid;
    }

    public EncryptedBucketSegment[] getEncryptedSegments() {
        return encryptedSegments;
    }

    @Override
    public int getSerializedSize() {
        int size = Integer.BYTES * 5 + encryptedSlotCur.length + encryptedMpCur.length;
        for (EncryptedBucketSegment segment : encryptedSegments) {
            size += segment.getSerializedSize();
        }
        return size;
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
        ORAMUtils.serializeInteger(pid, output, offset);
        offset += Integer.BYTES;
        ORAMUtils.serializeInteger(encryptedSegments.length, output, offset);
        offset += Integer.BYTES;
        for (EncryptedBucketSegment segment : encryptedSegments) {
            offset = segment.writeExternal(output, offset);
        }
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
        pid = ORAMUtils.deserializeInteger(input, offset);
        offset += Integer.BYTES;
        int count = ORAMUtils.deserializeInteger(input, offset);
        offset += Integer.BYTES;
        encryptedSegments = new EncryptedBucketSegment[count];
        for (int i = 0; i < count; i++) {
            EncryptedBucketSegment segment = new EncryptedBucketSegment();
            offset = segment.readExternal(input, offset);
            encryptedSegments[i] = segment;
        }
        return offset;
    }
}
