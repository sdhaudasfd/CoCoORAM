package oram.structure;

import oram.utils.ORAMUtils;
import oram.utils.RawCustomExternalizable;

public class EncryptedORAMPath implements RawCustomExternalizable {
    private int pid;
    private EncryptedORAMBucket[] buckets;

    public EncryptedORAMPath() {
        this.buckets = new EncryptedORAMBucket[0];
    }

    public EncryptedORAMPath(int pid, EncryptedORAMBucket[] buckets) {
        this.pid = pid;
        this.buckets = buckets;
    }

    public int getPid() {
        return pid;
    }

    public EncryptedORAMBucket[] getBuckets() {
        return buckets;
    }

    @Override
    public int getSerializedSize() {
        int size = Integer.BYTES * 2;
        for (EncryptedORAMBucket bucket : buckets) {
            size += bucket.getSerializedSize();
        }
        return size;
    }

    @Override
    public int writeExternal(byte[] output, int startOffset) {
        int offset = startOffset;

        ORAMUtils.serializeInteger(pid, output, offset);
        offset += Integer.BYTES;

        ORAMUtils.serializeInteger(buckets.length, output, offset);
        offset += Integer.BYTES;

        for (EncryptedORAMBucket bucket : buckets) {
            offset = bucket.writeExternal(output, offset);
        }

        return offset;
    }

    @Override
    public int readExternal(byte[] input, int startOffset) {
        int offset = startOffset;

        pid = ORAMUtils.deserializeInteger(input, offset);
        offset += Integer.BYTES;

        int length = ORAMUtils.deserializeInteger(input, offset);
        offset += Integer.BYTES;

        buckets = new EncryptedORAMBucket[length];
        for (int i = 0; i < length; i++) {
            EncryptedORAMBucket bucket = new EncryptedORAMBucket();
            offset = bucket.readExternal(input, offset);
            buckets[i] = bucket;
        }

        return offset;
    }
}
