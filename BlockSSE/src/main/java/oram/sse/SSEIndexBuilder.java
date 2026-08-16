package oram.sse;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SSEIndexBuilder {
    private SSEIndexBuilder() {
    }

    public static SSEIndex build(Path datasetPath, int blockSize) throws IOException {
        int leafCount = Integer.getInteger("oram.sse.leafCount", 1 << 18);
        long seed = Long.getLong("oram.sse.seed", 0x5EEDC0DEL);
        return build(datasetPath, blockSize, leafCount, seed);
    }

    public static SSEIndex build(Path datasetPath,
                                 int blockSize,
                                 int leafCount,
                                 long seed) throws IOException {
        EnronDataset dataset = EnronDataset.load(datasetPath);
        int documentsPerChunk = SSEChunkCodec.maxDocumentsPerChunk(blockSize);

        List<Integer> keywordIds = new ArrayList<>(dataset.getPostings().keySet());
        Collections.sort(keywordIds);

        Map<Integer, Integer> headBidByKeyword = new LinkedHashMap<>();
        Map<Integer, byte[]> encodedChunkByBid = new LinkedHashMap<>();
        int nextBid = 0;

        for (int keywordId : keywordIds) {
            List<Integer> postingList = dataset.getPostingList(keywordId);
            int chunkCount = Math.max(1, (postingList.size() + documentsPerChunk - 1) / documentsPerChunk);
            int firstBid = nextBid;
            headBidByKeyword.put(keywordId, firstBid);

            for (int chunkIndex = 0; chunkIndex < chunkCount; chunkIndex++) {
                int from = chunkIndex * documentsPerChunk;
                int to = Math.min(postingList.size(), from + documentsPerChunk);
                int[] documents = new int[to - from];
                for (int i = from; i < to; i++) {
                    documents[i - from] = postingList.get(i);
                }

                int bid = firstBid + chunkIndex;
                int followingBid = chunkIndex + 1 < chunkCount
                        ? bid + 1
                        : SSEChunk.END_OF_LIST;
                int followingPid = followingBid == SSEChunk.END_OF_LIST
                        ? SSEChunk.END_OF_LIST
                        : deterministicPid(followingBid, leafCount, seed);
                encodedChunkByBid.put(
                        bid,
                        SSEChunkCodec.encode(
                                new SSEChunk(
                                        keywordId,
                                        chunkIndex,
                                        followingBid,
                                        followingPid,
                                        documents
                                ),
                                blockSize
                        )
                );
                nextBid++;
            }
        }

        Map<Integer, List<Integer>> expected = new LinkedHashMap<>();
        for (int keywordId : keywordIds) {
            expected.put(
                    keywordId,
                    Collections.unmodifiableList(new ArrayList<>(dataset.getPostingList(keywordId)))
            );
        }

        return new SSEIndex(headBidByKeyword, encodedChunkByBid, expected);
    }

    public static int deterministicPid(int bid, int leafCount, long seed) {
        long value = seed ^ (0x9E3779B97F4A7C15L * (bid + 1L));
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        value ^= value >>> 31;
        return (int) Math.floorMod(value, leafCount);
    }
}
