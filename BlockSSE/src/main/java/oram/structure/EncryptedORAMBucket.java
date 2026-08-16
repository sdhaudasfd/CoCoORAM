package oram.structure;

import oram.utils.ORAMUtils;
import oram.utils.RawCustomExternalizable;

public class EncryptedORAMBucket implements RawCustomExternalizable {
    private int bucketId;
    private byte[][] encryptedBlocks;
    private int regionCapacityBytes;

    public EncryptedORAMBucket() {
        this.encryptedBlocks = new byte[0][];
        this.regionCapacityBytes = -1;
    }

    public EncryptedORAMBucket(int bucketId, byte[][] encryptedBlocks) {
        this.bucketId = bucketId;
        this.encryptedBlocks = encryptedBlocks == null ? new byte[0][] : encryptedBlocks;
        this.regionCapacityBytes = -1;
    }

    public EncryptedORAMBucket(int bucketId, int regionCapacityBytes, byte[][] encryptedRegions) {
        this.bucketId = bucketId;
        this.regionCapacityBytes = regionCapacityBytes;
        this.encryptedBlocks = encryptedRegions == null ? new byte[0][] : encryptedRegions;
    }

    public int getBucketId() {
        return bucketId;
    }

    public byte[][] getEncryptedBlocks() {
        return encryptedBlocks;
    }

    public boolean isWeighted() {
        return regionCapacityBytes > 0;
    }

    public int getRegionCapacityBytes() {
        return regionCapacityBytes;
    }

    @Override
    public int getSerializedSize() {
        int size = Integer.BYTES * 3;
        for (byte[] block : encryptedBlocks) {
            size += Integer.BYTES;
            size += block.length;
        }
        return size;
    }

    @Override
    public int writeExternal(byte[] output, int startOffset) {
        int offset = startOffset;

        ORAMUtils.serializeInteger(bucketId, output, offset);
        offset += Integer.BYTES;

        ORAMUtils.serializeInteger(regionCapacityBytes, output, offset);
        offset += Integer.BYTES;

        ORAMUtils.serializeInteger(encryptedBlocks.length, output, offset);
        offset += Integer.BYTES;

        for (byte[] block : encryptedBlocks) {
            ORAMUtils.serializeInteger(block.length, output, offset);
            offset += Integer.BYTES;

            System.arraycopy(block, 0, output, offset, block.length);
            offset += block.length;
        }

        return offset;
    }

    @Override
    public int readExternal(byte[] input, int startOffset) {
        int offset = startOffset;

        bucketId = ORAMUtils.deserializeInteger(input, offset);
        offset += Integer.BYTES;

        regionCapacityBytes = ORAMUtils.deserializeInteger(input, offset);
        offset += Integer.BYTES;

        int nBlocks = ORAMUtils.deserializeInteger(input, offset);
        offset += Integer.BYTES;

        encryptedBlocks = new byte[nBlocks][];
        for (int i = 0; i < nBlocks; i++) {
            int length = ORAMUtils.deserializeInteger(input, offset);
            offset += Integer.BYTES;

            encryptedBlocks[i] = new byte[length];
            System.arraycopy(input, offset, encryptedBlocks[i], 0, length);
            offset += length;
        }

        return offset;
    }
}
