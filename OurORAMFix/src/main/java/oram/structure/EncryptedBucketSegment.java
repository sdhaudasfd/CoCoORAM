package oram.structure;

import oram.utils.ORAMUtils;
import oram.utils.RawCustomExternalizable;

public class EncryptedBucketSegment implements RawCustomExternalizable {
    private int level;
    private int start;
    private byte[][] encryptedBlocks;

    public EncryptedBucketSegment() {
        this.level = 0;
        this.start = 0;
        this.encryptedBlocks = new byte[0][];
    }

    public EncryptedBucketSegment(int level, int start, byte[][] encryptedBlocks) {
        this.level = level;
        this.start = start;
        this.encryptedBlocks = encryptedBlocks == null ? new byte[0][] : encryptedBlocks;
    }

    public int getLevel() {
        return level;
    }

    public int getStart() {
        return start;
    }

    public byte[][] getEncryptedBlocks() {
        return encryptedBlocks;
    }

    @Override
    public int getSerializedSize() {
        int size = Integer.BYTES * 3;
        for (byte[] block : encryptedBlocks) {
            size += Integer.BYTES + block.length;
        }
        return size;
    }

    @Override
    public int writeExternal(byte[] output, int startOffset) {
        int offset = startOffset;
        ORAMUtils.serializeInteger(level, output, offset);
        offset += Integer.BYTES;
        ORAMUtils.serializeInteger(start, output, offset);
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
        level = ORAMUtils.deserializeInteger(input, offset);
        offset += Integer.BYTES;
        start = ORAMUtils.deserializeInteger(input, offset);
        offset += Integer.BYTES;
        int count = ORAMUtils.deserializeInteger(input, offset);
        offset += Integer.BYTES;

        encryptedBlocks = new byte[count][];
        for (int i = 0; i < count; i++) {
            int length = ORAMUtils.deserializeInteger(input, offset);
            offset += Integer.BYTES;
            encryptedBlocks[i] = new byte[length];
            System.arraycopy(input, offset, encryptedBlocks[i], 0, length);
            offset += length;
        }

        return offset;
    }
}
