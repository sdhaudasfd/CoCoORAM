package oram.sse;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public final class SSEIndexTool {
    private SSEIndexTool() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.out.println("Usage: oram.sse.SSEIndexTool <datasetPath> <blockSize>");
            System.exit(-1);
        }

        int blockSize = Integer.parseInt(args[1]);
        SSEIndex index = SSEIndexBuilder.build(Paths.get(args[0]), blockSize);
        int maxChunks = 0;
        long totalDocuments = 0L;

        for (int keywordId : index.getKeywordIds()) {
            int bid = index.getHeadBid(keywordId);
            int chunkCount = 0;
            List<Integer> decodedDocuments = new ArrayList<>();
            while (bid != SSEChunk.END_OF_LIST) {
                SSEChunk chunk = SSEChunkCodec.decode(index.getEncodedChunk(bid));
                if (chunk.getKeywordId() != keywordId || chunk.getChunkIndex() != chunkCount) {
                    throw new IllegalStateException("Broken chunk chain for keyword " + keywordId);
                }
                for (int documentId : chunk.getDocumentIds()) {
                    decodedDocuments.add(documentId);
                }
                bid = chunk.getNextBid();
                chunkCount++;
            }

            if (!decodedDocuments.equals(index.getExpectedPostingList(keywordId))) {
                throw new IllegalStateException("Posting-list mismatch for keyword " + keywordId);
            }
            maxChunks = Math.max(maxChunks, chunkCount);
            totalDocuments += decodedDocuments.size();
        }

        System.out.println("SSE index validation passed");
        System.out.println("keywords=" + index.getKeywordIds().size());
        System.out.println("chunks=" + index.getChunkCount());
        System.out.println("documentsPerChunk=" + SSEChunkCodec.maxDocumentsPerChunk(blockSize));
        System.out.println("maxChunksPerKeyword=" + maxChunks);
        System.out.println("keywordDocumentPairs=" + totalDocuments);
    }
}
