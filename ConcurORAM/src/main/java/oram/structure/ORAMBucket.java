package oram.structure;

import oram.utils.ORAMUtils;
import oram.utils.RawCustomExternalizable;

public class ORAMBucket implements RawCustomExternalizable {
    private int bucketId;
    private ORAMBlock[] blocks;

    public ORAMBucket() {
    }

    public ORAMBucket(int bucketId, int bucketSize, int blockSize) {
        this.bucketId = bucketId; // 0,1,2,3,...
        this.blocks = new ORAMBlock[bucketSize];

        for (int i = 0; i < bucketSize; i++) {
            blocks[i] = ORAMBlock.dummy(blockSize);
        }
    }

    public ORAMBucket(int bucketId, ORAMBlock[] blocks) {
        this.bucketId = bucketId;
        this.blocks = blocks;
    }

    public int getBucketId() {
        return bucketId;
    }

    public ORAMBlock[] getBlocks() {
        return blocks;
    }

    public ORAMBlock getBlock(int slot) {
        return blocks[slot];
    }

    public void setBlock(int slot, ORAMBlock block) {
        blocks[slot] = block;
    }

    public int size() {
        return blocks.length;
    }

    @Override
    public int getSerializedSize() {
        int size = Integer.BYTES * 2;
        for (ORAMBlock block : blocks) {
            size += block.getSerializedSize();
        }
        return size;
    }

    @Override
    public int writeExternal(byte[] output, int startOffset) {
        int offset = startOffset;

        ORAMUtils.serializeInteger(bucketId, output, offset);
        offset += Integer.BYTES;

        ORAMUtils.serializeInteger(blocks.length, output, offset);
        offset += Integer.BYTES;

        for (ORAMBlock block : blocks) {
            offset = block.writeExternal(output, offset);
        }

        return offset;
    }

    @Override
    public int readExternal(byte[] input, int startOffset) {
        int offset = startOffset;

        bucketId = ORAMUtils.deserializeInteger(input, offset);
        offset += Integer.BYTES;

        int bucketSize = ORAMUtils.deserializeInteger(input, offset);
        offset += Integer.BYTES;

        blocks = new ORAMBlock[bucketSize];
        for (int i = 0; i < bucketSize; i++) {
            ORAMBlock block = new ORAMBlock();
            offset = block.readExternal(input, offset);
            blocks[i] = block;
        }

        return offset;
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("ORAMBucket{bucketId=").append(bucketId).append(", blocks=[");

        for (int i = 0; i < blocks.length; i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(blocks[i]);
        }

        builder.append("]}");
        return builder.toString();
    }
}
