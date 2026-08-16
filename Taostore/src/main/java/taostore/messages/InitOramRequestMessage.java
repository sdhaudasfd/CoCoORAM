package taostore.messages;

import oram.common.ORAMUtils;

public class InitOramRequestMessage {
    private final int treeHeight;
    private final int bucketSize;
    private final int blockSize;

    public InitOramRequestMessage(int treeHeight, int bucketSize, int blockSize) {
        this.treeHeight = treeHeight;
        this.bucketSize = bucketSize;
        this.blockSize = blockSize;
    }

    public int getTreeHeight() {
        return treeHeight;
    }

    public int getBucketSize() {
        return bucketSize;
    }

    public int getBlockSize() {
        return blockSize;
    }

    public byte[] toBytes() {
        byte[] out = new byte[Integer.BYTES * 3];
        int offset = 0;
        ORAMUtils.serializeInteger(treeHeight, out, offset);
        offset += Integer.BYTES;
        ORAMUtils.serializeInteger(bucketSize, out, offset);
        offset += Integer.BYTES;
        ORAMUtils.serializeInteger(blockSize, out, offset);
        return out;
    }

    public static InitOramRequestMessage fromBytes(byte[] input) {
        int offset = 0;
        int treeHeight = ORAMUtils.deserializeInteger(input, offset);
        offset += Integer.BYTES;
        int bucketSize = ORAMUtils.deserializeInteger(input, offset);
        offset += Integer.BYTES;
        int blockSize = ORAMUtils.deserializeInteger(input, offset);
        return new InitOramRequestMessage(treeHeight, bucketSize, blockSize);
    }
}
