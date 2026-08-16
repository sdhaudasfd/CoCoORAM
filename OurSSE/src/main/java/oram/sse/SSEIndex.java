package oram.sse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SSEIndex {
    private final Map<Integer, Integer> headBidByKeyword;
    private final Map<Integer, byte[]> encodedChunkByBid;
    private final Map<Integer, List<Integer>> expectedPostings;

    SSEIndex(Map<Integer, Integer> headBidByKeyword,
             Map<Integer, byte[]> encodedChunkByBid,
             Map<Integer, List<Integer>> expectedPostings) {
        this.headBidByKeyword = Collections.unmodifiableMap(new LinkedHashMap<>(headBidByKeyword));
        this.encodedChunkByBid = Collections.unmodifiableMap(new LinkedHashMap<>(encodedChunkByBid));
        this.expectedPostings = Collections.unmodifiableMap(new LinkedHashMap<>(expectedPostings));
    }

    public int getHeadBid(int keywordId) {
        Integer bid = headBidByKeyword.get(keywordId);
        if (bid == null) {
            throw new IllegalArgumentException("Unknown keyword id " + keywordId);
        }
        return bid;
    }

    public byte[] getEncodedChunk(int bid) {
        byte[] encoded = encodedChunkByBid.get(bid);
        return encoded == null ? null : encoded.clone();
    }

    public Map<Integer, byte[]> getEncodedChunks() {
        return encodedChunkByBid;
    }

    public List<Integer> getExpectedPostingList(int keywordId) {
        List<Integer> result = expectedPostings.get(keywordId);
        return result == null ? Collections.emptyList() : result;
    }

    public List<Integer> getKeywordIds() {
        return new ArrayList<>(headBidByKeyword.keySet());
    }

    public int getChunkCount() {
        return encodedChunkByBid.size();
    }
}
