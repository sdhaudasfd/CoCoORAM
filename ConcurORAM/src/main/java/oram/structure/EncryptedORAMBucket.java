package oram.structure;

import oram.utils.ORAMUtils;
import oram.utils.RawCustomExternalizable;

public class EncryptedORAMBucket implements RawCustomExternalizable {
    private int bucketId;
    private byte[][] encryptedBlocks;

    public EncryptedORAMBucket() {
        this.encryptedBlocks = new byte[0][];
    }

    public EncryptedORAMBucket(int bucketId, byte[][] encryptedBlocks) {
        this.bucketId = bucketId;
        this.encryptedBlocks = encryptedBlocks == null ? new byte[0][] : encryptedBlocks;
    }

    public int getBucketId() {
        return bucketId;
    }

    public byte[][] getEncryptedBlocks() {
        return encryptedBlocks;
    }

    @Override
    public int getSerializedSize() {
        int size = Integer.BYTES * 2;
        for (byte[] block : encryptedBlocks) {
            size += Integer.BYTES;
            size += block == null ? 0 : block.length;
        }
        return size;
    }

    @Override
    public int writeExternal(byte[] output, int startOffset) {
        int offset = startOffset;

        ORAMUtils.serializeInteger(bucketId, output, offset);
        offset += Integer.BYTES;

        ORAMUtils.serializeInteger(encryptedBlocks.length, output, offset);
        offset += Integer.BYTES;

        for (byte[] block : encryptedBlocks) {
            int length = block == null ? -1 : block.length;
            ORAMUtils.serializeInteger(length, output, offset);
            offset += Integer.BYTES;

            if (length > 0) {
                System.arraycopy(block, 0, output, offset, length);
                offset += length;
            }
        }

        return offset;
    }

    @Override
    public int readExternal(byte[] input, int startOffset) {
        int offset = startOffset;

        bucketId = ORAMUtils.deserializeInteger(input, offset);
        offset += Integer.BYTES;

        int nBlocks = ORAMUtils.deserializeInteger(input, offset);
        offset += Integer.BYTES;

        encryptedBlocks = new byte[nBlocks][];
        for (int i = 0; i < nBlocks; i++) {
            int length = ORAMUtils.deserializeInteger(input, offset);
            offset += Integer.BYTES;

            if (length >= 0) {
                encryptedBlocks[i] = new byte[length];
                if (length > 0) {
                    System.arraycopy(input, offset, encryptedBlocks[i], 0, length);
                    offset += length;
                }
            } else {
                encryptedBlocks[i] = null;
            }
        }

        return offset;
    }
}
