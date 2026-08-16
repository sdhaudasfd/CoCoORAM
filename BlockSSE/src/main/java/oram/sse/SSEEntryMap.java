package oram.sse;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class SSEEntryMap {
    private final Map<Integer, Integer> headPidByKeyword = new ConcurrentHashMap<>();

    public void update(int keywordId, int pid) {
        headPidByKeyword.put(keywordId, pid);
    }

    public Integer get(int keywordId) {
        return headPidByKeyword.get(keywordId);
    }

    public int size() {
        return headPidByKeyword.size();
    }
}
