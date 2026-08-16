package taostore.proxy.structure;

import java.util.concurrent.ConcurrentHashMap;

public class ResponseMap {
    private static final byte[] PENDING = new byte[0];
    private final ConcurrentHashMap<Long, ResponseEntry> map = new ConcurrentHashMap<>();

    public static class ResponseEntry {
        volatile boolean ready;
        volatile byte[] value = PENDING;
    }

    public void create(long requestId) {
        map.put(requestId, new ResponseEntry());
    }

    public void markReady(long requestId) {
        ResponseEntry entry = map.get(requestId);
        if (entry != null) {
            entry.ready = true;
        }
    }

    public void setValue(long requestId, byte[] value) {
        ResponseEntry entry = map.get(requestId);
        if (entry != null) {
            entry.value = value == null ? new byte[0] : value;
        }
    }

    public ResponseEntry get(long requestId) {
        return map.get(requestId);
    }

    public byte[] getValue(long requestId) {
        ResponseEntry entry = map.get(requestId);
        return entry == null ? null : entry.value;
    }

    public void remove(long requestId) {
        map.remove(requestId);
    }

    public boolean canReply(long requestId) {
        ResponseEntry entry = map.get(requestId);
        return entry != null && entry.ready && entry.value != PENDING;
    }
}
