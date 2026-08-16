package oram.messages;

import oram.structure.EncryptedORAMBucket;
import oram.structure.EncryptedORAMPath;
import oram.utils.ORAMUtils;
import oram.utils.RawCustomExternalizable;

public final class ConcurMessages {
    private ConcurMessages() {
    }

    public static class Empty implements RawCustomExternalizable {
        @Override
        public int getSerializedSize() {
            return 0;
        }

        @Override
        public int writeExternal(byte[] output, int startOffset) {
            return startOffset;
        }

        @Override
        public int readExternal(byte[] input, int startOffset) {
            return startOffset;
        }
    }

    public static class IntRequest implements RawCustomExternalizable {
        private int value;

        public IntRequest() {
        }

        public IntRequest(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }

        @Override
        public int getSerializedSize() {
            return Integer.BYTES;
        }

        @Override
        public int writeExternal(byte[] output, int startOffset) {
            ORAMUtils.serializeInteger(value, output, startOffset);
            return startOffset + Integer.BYTES;
        }

        @Override
        public int readExternal(byte[] input, int startOffset) {
            value = ORAMUtils.deserializeInteger(input, startOffset);
            return startOffset + Integer.BYTES;
        }
    }

    public static class RegisterQueryRequest implements RawCustomExternalizable {
        private byte[] encryptedAddr;

        public RegisterQueryRequest() {
        }

        public RegisterQueryRequest(byte[] encryptedAddr) {
            this.encryptedAddr = encryptedAddr;
        }

        public byte[] getEncryptedAddr() {
            return encryptedAddr;
        }

        @Override
        public int getSerializedSize() {
            return Integer.BYTES + length(encryptedAddr);
        }

        @Override
        public int writeExternal(byte[] output, int startOffset) {
            return writeBytes(output, startOffset, encryptedAddr);
        }

        @Override
        public int readExternal(byte[] input, int startOffset) {
            BytesRead read = readBytes(input, startOffset);
            encryptedAddr = read.bytes;
            return read.offset;
        }
    }

    public static class RegisterQueryResponse implements RawCustomExternalizable {
        private int queryId;
        private int roundId;
        private byte[][] priorQl;

        public RegisterQueryResponse() {
        }

        public RegisterQueryResponse(int queryId, int roundId, byte[][] priorQl) {
            this.queryId = queryId;
            this.roundId = roundId;
            this.priorQl = priorQl;
        }

        public int getQueryId() {
            return queryId;
        }

        public int getRoundId() {
            return roundId;
        }

        public byte[][] getPriorQl() {
            return priorQl;
        }

        @Override
        public int getSerializedSize() {
            return Integer.BYTES * 3 + bytesSize(priorQl);
        }

        @Override
        public int writeExternal(byte[] output, int startOffset) {
            int offset = startOffset;
            ORAMUtils.serializeInteger(queryId, output, offset);
            offset += Integer.BYTES;
            ORAMUtils.serializeInteger(roundId, output, offset);
            offset += Integer.BYTES;
            offset = writeByteMatrix(output, offset, priorQl);
            return offset;
        }

        @Override
        public int readExternal(byte[] input, int startOffset) {
            int offset = startOffset;
            queryId = ORAMUtils.deserializeInteger(input, offset);
            offset += Integer.BYTES;
            roundId = ORAMUtils.deserializeInteger(input, offset);
            offset += Integer.BYTES;
            MatrixRead read = readByteMatrix(input, offset);
            priorQl = read.matrix;
            return read.offset;
        }
    }

    public static class MapUpdatesResponse implements RawCustomExternalizable {
        private int roundId;
        private byte[][] entries;

        public MapUpdatesResponse() {
        }

        public MapUpdatesResponse(int roundId, byte[][] entries) {
            this.roundId = roundId;
            this.entries = entries;
        }

        public int getRoundId() {
            return roundId;
        }

        public byte[][] getEntries() {
            return entries;
        }

        @Override
        public int getSerializedSize() {
            return Integer.BYTES * 2 + bytesSize(entries);
        }

        @Override
        public int writeExternal(byte[] output, int startOffset) {
            int offset = startOffset;
            ORAMUtils.serializeInteger(roundId, output, offset);
            offset += Integer.BYTES;
            offset = writeByteMatrix(output, offset, entries);
            return offset;
        }

        @Override
        public int readExternal(byte[] input, int startOffset) {
            int offset = startOffset;
            roundId = ORAMUtils.deserializeInteger(input, offset);
            offset += Integer.BYTES;
            MatrixRead read = readByteMatrix(input, offset);
            entries = read.matrix;
            return read.offset;
        }
    }

    public static class ByteMatrixResponse implements RawCustomExternalizable {
        private byte[][] entries;

        public ByteMatrixResponse() {
        }

        public ByteMatrixResponse(byte[][] entries) {
            this.entries = entries;
        }

        public byte[][] getEntries() {
            return entries;
        }

        @Override
        public int getSerializedSize() {
            return Integer.BYTES + bytesSize(entries);
        }

        @Override
        public int writeExternal(byte[] output, int startOffset) {
            return writeByteMatrix(output, startOffset, entries);
        }

        @Override
        public int readExternal(byte[] input, int startOffset) {
            MatrixRead read = readByteMatrix(input, startOffset);
            entries = read.matrix;
            return read.offset;
        }
    }

    public static class LogsAndStashesResponse implements RawCustomExternalizable {
        private byte[][] drlSetBlocks;
        private byte[][] mainStashBlocks;
        private byte[][] stashSetBlocks;

        public LogsAndStashesResponse() {
        }

        public LogsAndStashesResponse(byte[][] drlSetBlocks, byte[][] mainStashBlocks, byte[][] stashSetBlocks) {
            this.drlSetBlocks = drlSetBlocks;
            this.mainStashBlocks = mainStashBlocks;
            this.stashSetBlocks = stashSetBlocks;
        }

        public byte[][] getDrlSetBlocks() {
            return drlSetBlocks;
        }

        public byte[][] getMainStashBlocks() {
            return mainStashBlocks;
        }

        public byte[][] getStashSetBlocks() {
            return stashSetBlocks;
        }

        @Override
        public int getSerializedSize() {
            return Integer.BYTES * 3 + bytesSize(drlSetBlocks) + bytesSize(mainStashBlocks) + bytesSize(stashSetBlocks);
        }

        @Override
        public int writeExternal(byte[] output, int startOffset) {
            int offset = startOffset;
            offset = writeByteMatrix(output, offset, drlSetBlocks);
            offset = writeByteMatrix(output, offset, mainStashBlocks);
            offset = writeByteMatrix(output, offset, stashSetBlocks);
            return offset;
        }

        @Override
        public int readExternal(byte[] input, int startOffset) {
            MatrixRead first = readByteMatrix(input, startOffset);
            drlSetBlocks = first.matrix;
            MatrixRead second = readByteMatrix(input, first.offset);
            mainStashBlocks = second.matrix;
            MatrixRead third = readByteMatrix(input, second.offset);
            stashSetBlocks = third.matrix;
            return third.offset;
        }
    }

    public static class PathResponse implements RawCustomExternalizable {
        private EncryptedORAMPath path;

        public PathResponse() {
        }

        public PathResponse(EncryptedORAMPath path) {
            this.path = path;
        }

        public EncryptedORAMPath getPath() {
            return path;
        }

        @Override
        public int getSerializedSize() {
            return path.getSerializedSize();
        }

        @Override
        public int writeExternal(byte[] output, int startOffset) {
            return path.writeExternal(output, startOffset);
        }

        @Override
        public int readExternal(byte[] input, int startOffset) {
            path = new EncryptedORAMPath();
            return path.readExternal(input, startOffset);
        }
    }

    public static class ReadPathSlotRequest implements RawCustomExternalizable {
        private int pid;
        private int[] slots;

        public ReadPathSlotRequest() {
        }

        public ReadPathSlotRequest(int pid, int[] slots) {
            this.pid = pid;
            this.slots = slots;
        }

        public int getPid() {
            return pid;
        }

        public int[] getSlots() {
            return slots;
        }

        @Override
        public int getSerializedSize() {
            return Integer.BYTES * 2 + ((slots == null ? 0 : slots.length) * Integer.BYTES);
        }

        @Override
        public int writeExternal(byte[] output, int startOffset) {
            int offset = startOffset;
            ORAMUtils.serializeInteger(pid, output, offset);
            offset += Integer.BYTES;
            int count = slots == null ? 0 : slots.length;
            ORAMUtils.serializeInteger(count, output, offset);
            offset += Integer.BYTES;
            for (int i = 0; i < count; i++) {
                ORAMUtils.serializeInteger(slots[i], output, offset);
                offset += Integer.BYTES;
            }
            return offset;
        }

        @Override
        public int readExternal(byte[] input, int startOffset) {
            int offset = startOffset;
            pid = ORAMUtils.deserializeInteger(input, offset);
            offset += Integer.BYTES;
            int count = ORAMUtils.deserializeInteger(input, offset);
            offset += Integer.BYTES;
            slots = new int[count];
            for (int i = 0; i < count; i++) {
                slots[i] = ORAMUtils.deserializeInteger(input, offset);
                offset += Integer.BYTES;
            }
            return offset;
        }
    }

    public static class BlockResponse implements RawCustomExternalizable {
        private byte[] ciphertext;

        public BlockResponse() {
        }

        public BlockResponse(byte[] ciphertext) {
            this.ciphertext = ciphertext;
        }

        public byte[] getCiphertext() {
            return ciphertext;
        }

        @Override
        public int getSerializedSize() {
            return Integer.BYTES + length(ciphertext);
        }

        @Override
        public int writeExternal(byte[] output, int startOffset) {
            return writeBytes(output, startOffset, ciphertext);
        }

        @Override
        public int readExternal(byte[] input, int startOffset) {
            BytesRead read = readBytes(input, startOffset);
            ciphertext = read.bytes;
            return read.offset;
        }
    }

    public static class WriteQueryResultRequest implements RawCustomExternalizable {
        private int queryId;
        private byte[] encryptedBlock;
        private byte[] encryptedMapUpdate;

        public WriteQueryResultRequest() {
        }

        public WriteQueryResultRequest(int queryId, byte[] encryptedBlock, byte[] encryptedMapUpdate) {
            this.queryId = queryId;
            this.encryptedBlock = encryptedBlock;
            this.encryptedMapUpdate = encryptedMapUpdate;
        }

        public int getQueryId() {
            return queryId;
        }

        public byte[] getEncryptedBlock() {
            return encryptedBlock;
        }

        public byte[] getEncryptedMapUpdate() {
            return encryptedMapUpdate;
        }

        @Override
        public int getSerializedSize() {
            return Integer.BYTES + Integer.BYTES * 2 + length(encryptedBlock) + length(encryptedMapUpdate);
        }

        @Override
        public int writeExternal(byte[] output, int startOffset) {
            int offset = startOffset;
            ORAMUtils.serializeInteger(queryId, output, offset);
            offset += Integer.BYTES;
            offset = writeBytes(output, offset, encryptedBlock);
            offset = writeBytes(output, offset, encryptedMapUpdate);
            return offset;
        }

        @Override
        public int readExternal(byte[] input, int startOffset) {
            int offset = startOffset;
            queryId = ORAMUtils.deserializeInteger(input, offset);
            offset += Integer.BYTES;
            BytesRead first = readBytes(input, offset);
            encryptedBlock = first.bytes;
            BytesRead second = readBytes(input, first.offset);
            encryptedMapUpdate = second.bytes;
            return second.offset;
        }
    }

    public static class FinalizeRoundResponse implements RawCustomExternalizable {
        private int roundId;
        private byte[][] committedMapUpdates;

        public FinalizeRoundResponse() {
            this.committedMapUpdates = new byte[0][];
        }

        public FinalizeRoundResponse(int roundId) {
            this.roundId = roundId;
            this.committedMapUpdates = new byte[0][];
        }

        public FinalizeRoundResponse(int roundId, byte[][] committedMapUpdates) {
            this.roundId = roundId;
            this.committedMapUpdates = committedMapUpdates == null ? new byte[0][] : committedMapUpdates;
        }

        public int getRoundId() {
            return roundId;
        }

        public byte[][] getCommittedMapUpdates() {
            return committedMapUpdates;
        }

        @Override
        public int getSerializedSize() {
            int size = Integer.BYTES; // roundId
            size += Integer.BYTES;    // number of entries

            byte[][] entries = committedMapUpdates == null ? new byte[0][] : committedMapUpdates;
            for (byte[] entry : entries) {
                size += Integer.BYTES; // entry length
                if (entry != null) {
                    size += entry.length;
                }
            }

            return size;
        }

        @Override
        public int writeExternal(byte[] output, int startOffset) {
            int offset = startOffset;

            ORAMUtils.serializeInteger(roundId, output, offset);
            offset += Integer.BYTES;

            byte[][] entries = committedMapUpdates == null ? new byte[0][] : committedMapUpdates;

            ORAMUtils.serializeInteger(entries.length, output, offset);
            offset += Integer.BYTES;

            for (byte[] entry : entries) {
                int len = entry == null ? -1 : entry.length;
                ORAMUtils.serializeInteger(len, output, offset);
                offset += Integer.BYTES;

                if (len > 0) {
                    System.arraycopy(entry, 0, output, offset, len);
                    offset += len;
                }
            }

            return offset;
        }

        @Override
        public int readExternal(byte[] input, int startOffset) {
            int offset = startOffset;

            roundId = ORAMUtils.deserializeInteger(input, offset);
            offset += Integer.BYTES;

            int count = ORAMUtils.deserializeInteger(input, offset);
            offset += Integer.BYTES;

            committedMapUpdates = new byte[count][];

            for (int i = 0; i < count; i++) {
                int len = ORAMUtils.deserializeInteger(input, offset);
                offset += Integer.BYTES;

                if (len < 0) {
                    committedMapUpdates[i] = null;
                } else {
                    committedMapUpdates[i] = new byte[len];
                    if (len > 0) {
                        System.arraycopy(input, offset, committedMapUpdates[i], 0, len);
                        offset += len;
                    }
                }
            }

            return offset;
        }
    }

    public static class EvictionInputResponse implements RawCustomExternalizable {
        private int roundId;
        private int pid;
        private int evictionId;
        private byte[][] drlBlocks;
        private EncryptedORAMBucket[] nonCriticalBuckets;

        public EvictionInputResponse() {
        }
        
        public EvictionInputResponse(
                int evictionId,
                int roundId,
                int pid,
                byte[][] drlBlocks,
                EncryptedORAMBucket[] nonCriticalBuckets
        ) {
            this.evictionId = evictionId;
            this.roundId = roundId;
            this.pid = pid;
            this.drlBlocks = drlBlocks;
            this.nonCriticalBuckets = nonCriticalBuckets;
        }

        public int getEvictionId() {
            return evictionId;
        }

        public int getRoundId() {
            return roundId;
        }

        public int getPid() {
            return pid;
        }

        public byte[][] getDrlBlocks() {
            return drlBlocks;
        }

        public EncryptedORAMBucket[] getNonCriticalBuckets() {
            return nonCriticalBuckets;
        }

        @Override
        public int getSerializedSize() {
            return Integer.BYTES * 4 + bytesSize(drlBlocks) + bucketArraySize(nonCriticalBuckets);
        }

        @Override
        public int writeExternal(byte[] output, int startOffset) {
            int offset = startOffset;

            ORAMUtils.serializeInteger(evictionId, output, offset);
            offset += Integer.BYTES;

            ORAMUtils.serializeInteger(roundId, output, offset);
            offset += Integer.BYTES;

            ORAMUtils.serializeInteger(pid, output, offset);
            offset += Integer.BYTES;

            offset = writeByteMatrix(output, offset, drlBlocks);
            offset = writeBucketArray(output, offset, nonCriticalBuckets);

            return offset;
        }

        @Override
        public int readExternal(byte[] input, int startOffset) {
            int offset = startOffset;
            evictionId = ORAMUtils.deserializeInteger(input, offset);
            offset += Integer.BYTES;
            roundId = ORAMUtils.deserializeInteger(input, offset);
            offset += Integer.BYTES;
            pid = ORAMUtils.deserializeInteger(input, offset);
            offset += Integer.BYTES;
            MatrixRead drl = readByteMatrix(input, offset);
            drlBlocks = drl.matrix;
            BucketArrayRead buckets = readBucketArray(input, drl.offset);
            nonCriticalBuckets = buckets.buckets;
            return buckets.offset;
        }
    }

    public static class EvictionInputRequest implements RawCustomExternalizable {
        private int roundId;
        private int fromQueryId;
        private int toQueryId;

        public EvictionInputRequest() {
        }

        public EvictionInputRequest(int roundId, int fromQueryId, int toQueryId) {
            this.roundId = roundId;
            this.fromQueryId = fromQueryId;
            this.toQueryId = toQueryId;
        }

        public int getRoundId() {
            return roundId;
        }

        public int getFromQueryId() {
            return fromQueryId;
        }

        public int getToQueryId() {
            return toQueryId;
        }

        @Override
        public int getSerializedSize() {
            return Integer.BYTES * 3;
        }

        @Override
        public int writeExternal(byte[] output, int startOffset) {
            int offset = startOffset;
            ORAMUtils.serializeInteger(roundId, output, offset);
            offset += Integer.BYTES;
            ORAMUtils.serializeInteger(fromQueryId, output, offset);
            offset += Integer.BYTES;
            ORAMUtils.serializeInteger(toQueryId, output, offset);
            offset += Integer.BYTES;
            return offset;
        }

        @Override
        public int readExternal(byte[] input, int startOffset) {
            int offset = startOffset;
            roundId = ORAMUtils.deserializeInteger(input, offset);
            offset += Integer.BYTES;
            fromQueryId = ORAMUtils.deserializeInteger(input, offset);
            offset += Integer.BYTES;
            toQueryId = ORAMUtils.deserializeInteger(input, offset);
            offset += Integer.BYTES;
            return offset;
        }
    }

    public static class EvictionCriticalResponse implements RawCustomExternalizable {
        private byte[][] tempStashBlocks;
        private EncryptedORAMBucket[] criticalBuckets;

        public EvictionCriticalResponse() {
        }

        public EvictionCriticalResponse(byte[][] tempStashBlocks, EncryptedORAMBucket[] criticalBuckets) {
            this.tempStashBlocks = tempStashBlocks;
            this.criticalBuckets = criticalBuckets;
        }

        public byte[][] getTempStashBlocks() {
            return tempStashBlocks;
        }

        public EncryptedORAMBucket[] getCriticalBuckets() {
            return criticalBuckets;
        }

        @Override
        public int getSerializedSize() {
            return Integer.BYTES + bytesSize(tempStashBlocks) + bucketArraySize(criticalBuckets);
        }

        @Override
        public int writeExternal(byte[] output, int startOffset) {
            int offset = startOffset;
            offset = writeByteMatrix(output, offset, tempStashBlocks);
            offset = writeBucketArray(output, offset, criticalBuckets);
            return offset;
        }

        @Override
        public int readExternal(byte[] input, int startOffset) {
            MatrixRead stash = readByteMatrix(input, startOffset);
            tempStashBlocks = stash.matrix;
            BucketArrayRead buckets = readBucketArray(input, stash.offset);
            criticalBuckets = buckets.buckets;
            return buckets.offset;
        }
    }

    public static class SubmitEvictionRequest implements RawCustomExternalizable {
        private int roundId;
        private EncryptedORAMPath path;
        private byte[][] tempStashBlocks;
        private byte[][] mapUpdates;

        public SubmitEvictionRequest() {
        }

        public SubmitEvictionRequest(
                int roundId,
                EncryptedORAMPath path,
                byte[][] tempStashBlocks,
                byte[][] mapUpdates
        ) {
            this.roundId = roundId;
            this.path = path;
            this.tempStashBlocks = tempStashBlocks;
            this.mapUpdates = mapUpdates == null ? new byte[0][] : mapUpdates;
        }

        public int getRoundId() {
            return roundId;
        }

        public EncryptedORAMPath getPath() {
            return path;
        }

        public byte[][] getTempStashBlocks() {
            return tempStashBlocks;
        }

        public byte[][] getMapUpdates() {
            return mapUpdates;
        }

        @Override
        public int getSerializedSize() {
            return Integer.BYTES
                    + path.getSerializedSize()
                    + Integer.BYTES + bytesSize(tempStashBlocks)
                    + Integer.BYTES + bytesSize(mapUpdates);
        }

        @Override
        public int writeExternal(byte[] output, int startOffset) {
            int offset = startOffset;

            ORAMUtils.serializeInteger(roundId, output, offset);
            offset += Integer.BYTES;

            offset = path.writeExternal(output, offset);
            offset = writeByteMatrix(output, offset, tempStashBlocks);
            offset = writeByteMatrix(output, offset, mapUpdates);

            return offset;
        }

        @Override
        public int readExternal(byte[] input, int startOffset) {
            int offset = startOffset;

            roundId = ORAMUtils.deserializeInteger(input, offset);
            offset += Integer.BYTES;

            path = new EncryptedORAMPath();
            offset = path.readExternal(input, offset);

            MatrixRead stash = readByteMatrix(input, offset);
            tempStashBlocks = stash.matrix;
            offset = stash.offset;

            MatrixRead maps = readByteMatrix(input, offset);
            mapUpdates = maps.matrix;
            offset = maps.offset;

            return offset;
        }
    }

    private static int length(byte[] bytes) {
        return bytes == null ? 0 : bytes.length;
    }

    private static int bytesSize(byte[][] entries) {
        int size = 0;
        if (entries != null) {
            for (byte[] entry : entries) {
                size += Integer.BYTES + length(entry);
            }
        }
        return size;
    }

    private static int bucketArraySize(EncryptedORAMBucket[] buckets) {
        int size = Integer.BYTES;
        if (buckets != null) {
            for (EncryptedORAMBucket bucket : buckets) {
                size += bucket.getSerializedSize();
            }
        }
        return size;
    }

    private static int writeBytes(byte[] output, int offset, byte[] bytes) {
        int length = bytes == null ? -1 : bytes.length;
        ORAMUtils.serializeInteger(length, output, offset);
        offset += Integer.BYTES;
        if (length > 0) {
            System.arraycopy(bytes, 0, output, offset, length);
            offset += length;
        }
        return offset;
    }

    private static BytesRead readBytes(byte[] input, int offset) {
        int length = ORAMUtils.deserializeInteger(input, offset);
        offset += Integer.BYTES;
        if (length < 0) {
            return new BytesRead(null, offset);
        }
        byte[] bytes = new byte[length];
        if (length > 0) {
            System.arraycopy(input, offset, bytes, 0, length);
            offset += length;
        }
        return new BytesRead(bytes, offset);
    }

    private static int writeByteMatrix(byte[] output, int offset, byte[][] entries) {
        int count = entries == null ? 0 : entries.length;
        ORAMUtils.serializeInteger(count, output, offset);
        offset += Integer.BYTES;
        if (entries != null) {
            for (byte[] entry : entries) {
                offset = writeBytes(output, offset, entry);
            }
        }
        return offset;
    }

    private static MatrixRead readByteMatrix(byte[] input, int offset) {
        int count = ORAMUtils.deserializeInteger(input, offset);
        offset += Integer.BYTES;
        byte[][] matrix = new byte[count][];
        for (int i = 0; i < count; i++) {
            BytesRead read = readBytes(input, offset);
            matrix[i] = read.bytes;
            offset = read.offset;
        }
        return new MatrixRead(matrix, offset);
    }

    private static int writeBucketArray(byte[] output, int offset, EncryptedORAMBucket[] buckets) {
        int count = buckets == null ? 0 : buckets.length;
        ORAMUtils.serializeInteger(count, output, offset);
        offset += Integer.BYTES;
        if (buckets != null) {
            for (EncryptedORAMBucket bucket : buckets) {
                offset = bucket.writeExternal(output, offset);
            }
        }
        return offset;
    }

    private static BucketArrayRead readBucketArray(byte[] input, int offset) {
        int count = ORAMUtils.deserializeInteger(input, offset);
        offset += Integer.BYTES;
        EncryptedORAMBucket[] buckets = new EncryptedORAMBucket[count];
        for (int i = 0; i < count; i++) {
            EncryptedORAMBucket bucket = new EncryptedORAMBucket();
            offset = bucket.readExternal(input, offset);
            buckets[i] = bucket;
        }
        return new BucketArrayRead(buckets, offset);
    }

    private static class BytesRead {
        private final byte[] bytes;
        private final int offset;

        private BytesRead(byte[] bytes, int offset) {
            this.bytes = bytes;
            this.offset = offset;
        }
    }

    private static class MatrixRead {
        private final byte[][] matrix;
        private final int offset;

        private MatrixRead(byte[][] matrix, int offset) {
            this.matrix = matrix;
            this.offset = offset;
        }
    }

    private static class BucketArrayRead {
        private final EncryptedORAMBucket[] buckets;
        private final int offset;

        private BucketArrayRead(EncryptedORAMBucket[] buckets, int offset) {
            this.buckets = buckets;
            this.offset = offset;
        }
    }
}
