package oram.benchmark;

import oram.client.BlockSSEAccessExecutor;
import oram.client.StrongMVPManager;
import oram.sse.SSEChunk;
import oram.sse.SSEEntryMap;
import oram.sse.SSEIndex;
import oram.sse.SSEIndexBuilder;
import oram.structure.ORAMBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

public final class BlockSSEBenchmark {
    private static final Logger logger = LoggerFactory.getLogger("benchmarking");
    private static final int FIRST_CLIENT_ID = 1;
    private static final long INDEX_SEED = 0x5EEDC0DEL;

    public static void main(String[] args) throws Exception {
        if (args.length != 10) {
            System.out.println("Usage: oram.benchmark.BlockSSEBenchmark " +
                    "<searchesPerClient> <nClients> <bidExponent> <rootBucketSize> " +
                    "<bucketSize> <blockSize> <datasetPath> <queryMode> <serverIp> <serverPort>");
            System.exit(-1);
        }

        int searchesPerClient = Integer.parseInt(args[0]);
        int nClients = Integer.parseInt(args[1]);
        int bidExponent = Integer.parseInt(args[2]);
        int rootBucketSize = Integer.parseInt(args[3]);
        int bucketSize = Integer.parseInt(args[4]);
        int blockSize = Integer.parseInt(args[5]);
        String datasetPath = args[6];
        String queryMode = args[7];
        String serverIp = args[8];
        int serverPort = Integer.parseInt(args[9]);
        int leafCount = 1 << bidExponent;

        SSEIndex index = SSEIndexBuilder.build(
                Paths.get(datasetPath),
                blockSize,
                leafCount,
                INDEX_SEED
        );
        SSEEntryMap entryMap = new SSEEntryMap();
        for (int keywordId : index.getKeywordIds()) {
            entryMap.update(
                    keywordId,
                    SSEIndexBuilder.deterministicPid(index.getHeadBid(keywordId), leafCount, INDEX_SEED)
            );
        }

        Map<Integer, ORAMBlock> sharedStash = Collections.synchronizedMap(new HashMap<>());
        CountDownLatch ready = new CountDownLatch(nClients);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Client[] clients = new Client[nClients];
        for (int i = 0; i < nClients; i++) {
            clients[i] = new Client(
                    FIRST_CLIENT_ID + i,
                    searchesPerClient,
                    bidExponent,
                    rootBucketSize,
                    bucketSize,
                    blockSize,
                    queryMode,
                    serverIp,
                    serverPort,
                    index,
                    entryMap,
                    sharedStash,
                    ready,
                    start,
                    failure
            );
            clients[i].start();
        }

        ready.await();
        logger.info("Executing BlockSSE experiment, queryMode={}", queryMode);
        start.countDown();
        for (Client client : clients) {
            client.join();
        }
        if (failure.get() != null) {
            throw new RuntimeException("BlockSSE benchmark failed", failure.get());
        }

        long searches = 0;
        long chunks = 0;
        long latencyNs = 0;
        long queueNs = 0;
        long protocolBytes = 0;
        long earliestStartNs = Long.MAX_VALUE;
        long latestEndNs = Long.MIN_VALUE;
        for (Client client : clients) {
            searches += client.completedSearches;
            chunks += client.completedChunks;
            latencyNs += client.totalLatencyNs;
            queueNs += client.totalQueueNs;
            protocolBytes += client.getProtocolBytes();
            earliestStartNs = Math.min(earliestStartNs, client.firstSearchStartNs);
            latestEndNs = Math.max(latestEndNs, client.lastSearchEndNs);
        }
        double wallSec = searches == 0
                ? 0.0
                : (latestEndNs - earliestStartNs) / 1_000_000_000.0;
        double searchThroughput = searches / wallSec;
        double chunkThroughput = chunks / wallSec;
        double averageLatencyMs = searches == 0 ? 0 : latencyNs / 1_000_000.0 / searches;
        double averageQueueMs = searches == 0 ? 0 : queueNs / 1_000_000.0 / searches;
        double averageBytesPerSearch = searches == 0 ? 0 : (double) protocolBytes / searches;
        double averageBytesPerChunk = chunks == 0 ? 0 : (double) protocolBytes / chunks;

        logger.info(String.format(
                "BlockSSE Benchmark Summary:%n" +
                        "\tConcurrent clients[#]: %d%n" +
                        "\tSearches per client[#]: %d%n" +
                        "\tCompleted searches[#]: %d%n" +
                        "\tUseful chunk accesses[#]: %d%n" +
                        "\tWall-clock time[s]: %.3f%n" +
                        "\tSearch throughput[searches/s]: %.3f%n" +
                        "\tChunk throughput[chunks/s]: %.3f%n" +
                        "\tAverage search latency[ms]: %.3f%n" +
                        "\tAverage queue wait[ms]: %.3f%n" +
                        "\tAverage protocol bandwidth/search[bytes]: %.3f%n" +
                        "\tAverage protocol bandwidth/chunk[bytes]: %.3f%n" +
                        "\tClient stash blocks[#]: %d",
                nClients,
                searchesPerClient,
                searches,
                chunks,
                wallSec,
                searchThroughput,
                chunkThroughput,
                averageLatencyMs,
                averageQueueMs,
                averageBytesPerSearch,
                averageBytesPerChunk,
                sharedStash.size()
        ));
    }

    private static final class Client extends Thread {
        private final int searches;
        private final String queryMode;
        private final SSEIndex index;
        private final SSEEntryMap entryMap;
        private final CountDownLatch ready;
        private final CountDownLatch start;
        private final AtomicReference<Throwable> failure;
        private final StrongMVPManager manager;
        private final BlockSSEAccessExecutor executor;
        private final Random random;

        private long completedSearches;
        private long completedChunks;
        private long totalLatencyNs;
        private long totalQueueNs;
        private long firstSearchStartNs = Long.MAX_VALUE;
        private long lastSearchEndNs = Long.MIN_VALUE;

        private Client(int clientId,
                       int searches,
                       int bidExponent,
                       int rootBucketSize,
                       int bucketSize,
                       int blockSize,
                       String queryMode,
                       String serverIp,
                       int serverPort,
                       SSEIndex index,
                       SSEEntryMap entryMap,
                       Map<Integer, ORAMBlock> sharedStash,
                       CountDownLatch ready,
                       CountDownLatch start,
                       AtomicReference<Throwable> failure) {
            this.searches = searches;
            this.queryMode = queryMode;
            this.index = index;
            this.entryMap = entryMap;
            this.ready = ready;
            this.start = start;
            this.failure = failure;
            this.manager = new StrongMVPManager(clientId, serverIp, serverPort);
            this.executor = new BlockSSEAccessExecutor(
                    manager,
                    1 << bidExponent,
                    bidExponent + 1,
                    rootBucketSize,
                    bucketSize,
                    blockSize,
                    sharedStash
            );
            this.random = new Random(INDEX_SEED + clientId);
        }

        @Override
        public void run() {
            try {
                ready.countDown();
                start.await();
                List<Integer> keywords = index.getKeywordIds();
                for (int i = 0; i < searches && failure.get() == null; i++) {
                    int keyword = selectKeyword(keywords);
                    long begin = System.nanoTime();
                    firstSearchStartNs = Math.min(firstSearchStartNs, begin);
                    manager.acquireSearch();
                    long acquired = System.nanoTime();
                    try {
                        List<Integer> actual = search(keyword);
                        if (!actual.equals(index.getExpectedPostingList(keyword))) {
                            throw new IllegalStateException(
                                    "Posting mismatch for keyword " + keyword +
                                            ": expected=" + index.getExpectedPostingList(keyword).size() +
                                            ", actual=" + actual.size()
                            );
                        }
                    } finally {
                        manager.releaseSearch();
                    }
                    long end = System.nanoTime();
                    lastSearchEndNs = Math.max(lastSearchEndNs, end);
                    completedSearches++;
                    totalQueueNs += acquired - begin;
                    totalLatencyNs += end - begin;
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
                t.printStackTrace();
            } finally {
                manager.close();
            }
        }

        private List<Integer> search(int keyword) {
            Integer headPid = entryMap.get(keyword);
            if (headPid == null) {
                throw new IllegalStateException("Missing head position for keyword " + keyword);
            }
            int bid = index.getHeadBid(keyword);
            int readPid = headPid;
            int writePid = executor.randomPid();
            int expectedChunkIndex = 0;
            List<Integer> documents = new ArrayList<>();

            while (bid != SSEChunk.END_OF_LIST) {
                int successorWritePid = executor.randomPid();
                BlockSSEAccessExecutor.ChunkAccessResult result =
                        executor.accessChunk(bid, readPid, writePid, successorWritePid);
                SSEChunk chunk = result.getChunk();
                if (chunk.getKeywordId() != keyword ||
                        chunk.getChunkIndex() != expectedChunkIndex) {
                    throw new IllegalStateException(
                            "Broken chunk chain for keyword " + keyword +
                                    " at chunk " + expectedChunkIndex
                    );
                }
                if (expectedChunkIndex == 0) {
                    entryMap.update(keyword, result.getWritePid());
                }
                for (int documentId : chunk.getDocumentIds()) {
                    documents.add(documentId);
                }
                bid = chunk.getNextBid();
                readPid = chunk.getNextPid();
                writePid = result.getSuccessorWritePid();
                expectedChunkIndex++;
                completedChunks++;
            }
            return documents;
        }

        private int selectKeyword(List<Integer> keywords) {
            if (queryMode.startsWith("hotspot:")) {
                int count = Integer.parseInt(queryMode.substring("hotspot:".length()));
                int bound = Math.max(1, Math.min(count, keywords.size()));
                return keywords.get(random.nextInt(bound));
            }
            if (!"uniform".equals(queryMode)) {
                throw new IllegalArgumentException("Unsupported query mode " + queryMode);
            }
            return keywords.get(random.nextInt(keywords.size()));
        }

        private long getProtocolBytes() {
            return manager.getProtocolBytes();
        }
    }
}
