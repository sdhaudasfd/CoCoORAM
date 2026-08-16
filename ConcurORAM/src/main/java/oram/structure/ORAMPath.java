package oram.structure;

import oram.utils.ORAMUtils;
import oram.utils.RawCustomExternalizable;

public class ORAMPath implements RawCustomExternalizable {
    private int pid;
    private ORAMBucket[] buckets;

    public ORAMPath() {
    }

    public ORAMPath(int pid, ORAMBucket[] buckets) {
        this.pid = pid;
        this.buckets = buckets;
    }

    public int getPid() {
        return pid;
    }

    public ORAMBucket[] getBuckets() {
        return buckets;
    }

    public ORAMBucket getBucket(int index) {
        return buckets[index];
    }

    public int length() {
        return buckets.length;
    }

    public ORAMBlock findLatestBlock(int bid) {
        ORAMBlock latest = null;

        for (ORAMBucket bucket : buckets) {
            for (ORAMBlock block : bucket.getBlocks()) {
                if (block == null || block.isDummy()) {
                    continue;
                }

                if (block.getBid() != bid) {
                    continue;
                }

                if (latest == null || block.getSeq() > latest.getSeq()) {
                    latest = block;
                }
            }
        }

        return latest;
    }

    @Override
    public int getSerializedSize() {
        int size = Integer.BYTES * 2;

        for (ORAMBucket bucket : buckets) {
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

        for (ORAMBucket bucket : buckets) {
            offset = bucket.writeExternal(output, offset);
        }

        return offset;
    }

    @Override
    public int readExternal(byte[] input, int startOffset) {
        int offset = startOffset;

        pid = ORAMUtils.deserializeInteger(input, offset);
        offset += Integer.BYTES;

        int pathLength = ORAMUtils.deserializeInteger(input, offset);
        offset += Integer.BYTES;

        buckets = new ORAMBucket[pathLength];
        for (int i = 0; i < pathLength; i++) {
            ORAMBucket bucket = new ORAMBucket();
            offset = bucket.readExternal(input, offset);
            buckets[i] = bucket;
        }

        return offset;
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("ORAMPath{pid=").append(pid).append(", buckets=[");

        for (int i = 0; i < buckets.length; i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(buckets[i]);
        }

        builder.append("]}");
        return builder.toString();
    }
}
