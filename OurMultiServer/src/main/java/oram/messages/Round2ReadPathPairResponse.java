package oram.messages;

import oram.structure.EncryptedORAMBucket;
import oram.structure.EncryptedORAMPath;
import oram.utils.ORAMUtils;
import oram.utils.RawCustomExternalizable;

public class Round2ReadPathPairResponse implements RawCustomExternalizable {
    private EncryptedORAMPath firstPath;
    private int secondPid;
    private int sharedPrefixLength;
    private EncryptedORAMBucket[] secondSuffixBuckets;

    public Round2ReadPathPairResponse() {
        this.firstPath = new EncryptedORAMPath();
        this.secondSuffixBuckets = new EncryptedORAMBucket[0];
    }

    public Round2ReadPathPairResponse(EncryptedORAMPath firstPath,
                                      int secondPid,
                                      int sharedPrefixLength,
                                      EncryptedORAMBucket[] secondSuffixBuckets) {
        this.firstPath = firstPath;
        this.secondPid = secondPid;
        this.sharedPrefixLength = sharedPrefixLength;
        this.secondSuffixBuckets = secondSuffixBuckets == null ? new EncryptedORAMBucket[0] : secondSuffixBuckets;
    }

    public EncryptedORAMPath getFirstPath() {
        return firstPath;
    }

    public int getSecondPid() {
        return secondPid;
    }

    public int getSharedPrefixLength() {
        return sharedPrefixLength;
    }

    public EncryptedORAMBucket[] getSecondSuffixBuckets() {
        return secondSuffixBuckets;
    }

    @Override
    public int getSerializedSize() {
        int size = firstPath.getSerializedSize() + Integer.BYTES * 3;
        for (EncryptedORAMBucket bucket : secondSuffixBuckets) {
            size += bucket.getSerializedSize();
        }
        return size;
    }

    @Override
    public int writeExternal(byte[] output, int startOffset) {
        int offset = firstPath.writeExternal(output, startOffset);
        ORAMUtils.serializeInteger(secondPid, output, offset);
        offset += Integer.BYTES;
        ORAMUtils.serializeInteger(sharedPrefixLength, output, offset);
        offset += Integer.BYTES;
        ORAMUtils.serializeInteger(secondSuffixBuckets.length, output, offset);
        offset += Integer.BYTES;
        for (EncryptedORAMBucket bucket : secondSuffixBuckets) {
            offset = bucket.writeExternal(output, offset);
        }
        return offset;
    }

    @Override
    public int readExternal(byte[] input, int startOffset) {
        int offset = startOffset;
        firstPath = new EncryptedORAMPath();
        offset = firstPath.readExternal(input, offset);
        secondPid = ORAMUtils.deserializeInteger(input, offset);
        offset += Integer.BYTES;
        sharedPrefixLength = ORAMUtils.deserializeInteger(input, offset);
        offset += Integer.BYTES;
        int suffixLength = ORAMUtils.deserializeInteger(input, offset);
        offset += Integer.BYTES;
        secondSuffixBuckets = new EncryptedORAMBucket[suffixLength];
        for (int i = 0; i < suffixLength; i++) {
            EncryptedORAMBucket bucket = new EncryptedORAMBucket();
            offset = bucket.readExternal(input, offset);
            secondSuffixBuckets[i] = bucket;
        }
        return offset;
    }
}
