package oram.sse;

import java.util.Arrays;

public final class SSEChunk {
    public static final int END_OF_LIST = -1;

    private final int keywordId;
    private final int chunkIndex;
    private final int nextBid;
    private final int nextPid;
    private final int[] documentIds;

    public SSEChunk(int keywordId, int chunkIndex, int nextBid, int nextPid, int[] documentIds) {
        this.keywordId = keywordId;
        this.chunkIndex = chunkIndex;
        this.nextBid = nextBid;
        this.nextPid = nextPid;
        this.documentIds = Arrays.copyOf(documentIds, documentIds.length);
    }

    public int getKeywordId() {
        return keywordId;
    }

    public int getChunkIndex() {
        return chunkIndex;
    }

    public int getNextBid() {
        return nextBid;
    }

    public int getNextPid() {
        return nextPid;
    }

    public int[] getDocumentIds() {
        return Arrays.copyOf(documentIds, documentIds.length);
    }
}
