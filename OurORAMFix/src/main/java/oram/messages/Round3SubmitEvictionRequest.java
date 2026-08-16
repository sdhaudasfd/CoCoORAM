package oram.messages;

import oram.structure.EncryptedBucketSegment;
import oram.utils.ORAMUtils;
import oram.utils.RawCustomExternalizable;

public class Round3SubmitEvictionRequest implements RawCustomExternalizable {
    private int seq;
    private int pid;
    private EncryptedBucketSegment[] encryptedSegments;

    public Round3SubmitEvictionRequest() {
        this.seq = 0;
        this.pid = 0;
        this.encryptedSegments = new EncryptedBucketSegment[0];
    }

    public Round3SubmitEvictionRequest(int seq, int pid, EncryptedBucketSegment[] encryptedSegments) {
        this.seq = seq;
        this.pid = pid;
        this.encryptedSegments = encryptedSegments == null ? new EncryptedBucketSegment[0] : encryptedSegments;
    }

    public int getSeq() {
        return seq;
    }

    public int getPid() {
        return pid;
    }

    public EncryptedBucketSegment[] getEncryptedSegments() {
        return encryptedSegments;
    }

    @Override
    public int getSerializedSize() {
        int size = Integer.BYTES * 3;
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
