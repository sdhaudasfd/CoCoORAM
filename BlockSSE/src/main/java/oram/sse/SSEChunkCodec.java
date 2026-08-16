package oram.sse;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class SSEChunkCodec {
    private static final int MAGIC = 0x53534531;
    private static final int VERSION = 2;
    public static final int HEADER_BYTES = Integer.BYTES * 7;

    private SSEChunkCodec() {
    }

    public static int maxDocumentsPerChunk(int blockSize) {
        int capacity = (blockSize - HEADER_BYTES) / Integer.BYTES;
        if (capacity <= 0) {
            throw new IllegalArgumentException(
                    "blockSize must be at least " + (HEADER_BYTES + Integer.BYTES) + " bytes"
            );
        }
        return capacity;
    }

    public static byte[] encode(SSEChunk chunk, int blockSize) {
        int[] documents = chunk.getDocumentIds();
        int maxDocuments = maxDocumentsPerChunk(blockSize);
        if (documents.length > maxDocuments) {
            throw new IllegalArgumentException(
                    "Chunk has " + documents.length + " documents, capacity is " + maxDocuments
            );
        }

        ByteBuffer buffer = ByteBuffer.allocate(HEADER_BYTES + documents.length * Integer.BYTES)
                .order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(MAGIC);
        buffer.putInt(VERSION);
        buffer.putInt(chunk.getKeywordId());
        buffer.putInt(chunk.getChunkIndex());
        buffer.putInt(chunk.getNextBid());
        buffer.putInt(chunk.getNextPid());
        buffer.putInt(documents.length);
        for (int documentId : documents) {
            buffer.putInt(documentId);
        }
        return buffer.array();
    }

    public static SSEChunk decode(byte[] encoded) {
        if (encoded == null || encoded.length < HEADER_BYTES) {
            throw new IllegalArgumentException("Encoded chunk is missing or truncated");
        }

        ByteBuffer buffer = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN);
        int magic = buffer.getInt();
        int version = buffer.getInt();
        if (magic != MAGIC || version != VERSION) {
            throw new IllegalArgumentException("Data is not an SSE chunk");
        }

        int keywordId = buffer.getInt();
        int chunkIndex = buffer.getInt();
        int nextBid = buffer.getInt();
        int nextPid = buffer.getInt();
        int documentCount = buffer.getInt();
        int maxDocuments = (encoded.length - HEADER_BYTES) / Integer.BYTES;
        if (documentCount < 0 || documentCount > maxDocuments) {
            throw new IllegalArgumentException("Invalid SSE chunk document count " + documentCount);
        }

        int[] documents = new int[documentCount];
        for (int i = 0; i < documentCount; i++) {
            documents[i] = buffer.getInt();
        }
        return new SSEChunk(keywordId, chunkIndex, nextBid, nextPid, documents);
    }

    public static byte[] scratchBlock(int blockSize, int scratchId) {
        return encode(
                new SSEChunk(
                        Integer.MIN_VALUE,
                        scratchId,
                        SSEChunk.END_OF_LIST,
                        SSEChunk.END_OF_LIST,
                        new int[0]
                ),
                blockSize
        );
    }
}
