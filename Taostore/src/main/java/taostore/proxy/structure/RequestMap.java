package taostore.proxy.structure;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

public class RequestMap {
    private final ConcurrentHashMap<Integer, ReentrantLock> bidLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, ArrayDeque<LogicalRequest>> queues = new ConcurrentHashMap<>();

    private ReentrantLock lockFor(int bid) {
        return bidLocks.computeIfAbsent(bid, k -> new ReentrantLock());
    }

    public boolean enqueue(LogicalRequest req) {
        int bid = req.getBlockAddress();
        ReentrantLock lock = lockFor(bid);
        lock.lock();
        try {
            ArrayDeque<LogicalRequest> queue = queues.computeIfAbsent(bid, k -> new ArrayDeque<>());
            boolean isFirst = queue.isEmpty();
            queue.addLast(req);
            return isFirst;
        } finally {
            lock.unlock();
        }
    }

    public LogicalRequest peek(int blockAddress) {
        ReentrantLock lock = lockFor(blockAddress);
        lock.lock();
        try {
            ArrayDeque<LogicalRequest> queue = queues.get(blockAddress);
            return queue == null ? null : queue.peekFirst();
        } finally {
            lock.unlock();
        }
    }

    public List<LogicalRequest> drainQueue(int blockAddress) {
        ReentrantLock lock = lockFor(blockAddress);
        lock.lock();
        try {
            ArrayDeque<LogicalRequest> queue = queues.remove(blockAddress);
            if (queue == null || queue.isEmpty()) {
                return new ArrayList<>();
            }
            return new ArrayList<>(queue);
        } finally {
            lock.unlock();
        }
    }
}
