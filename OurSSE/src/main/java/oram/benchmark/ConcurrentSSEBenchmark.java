package oram.benchmark;

import oram.client.ClientAccessExecutor;
import oram.client.AccessDataTransformer;
import oram.client.ORAMManager;
import oram.client.module.ClientAccessResult;
import oram.client.module.DelayedRequest;
import oram.sse.SSEChunk;
import oram.sse.SSEChunkCodec;
import oram.sse.SSEEntryMap;
import oram.sse.SSEIndex;
import oram.sse.SSEIndexBuilder;
import oram.utils.Operation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Phaser;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class ConcurrentSSEBenchmark {
    private static final Logger logger = LoggerFactory.getLogger("benchmarking");
    private static final int FIRST_CLIENT_ID = 1;

    public static void main(String[] args) throws Exception {
        if (args.length != 11) {
            System.out.println(
                    "Usage: oram.benchmark.ConcurrentSSEBenchmark " +
                            "<searchesPerClient> <nClients> <bidExponent> <rootBucketSize> " +
                            "<competitionBucketSize> <bucketSize> <blockSize> <datasetPath> " +
                            "<queryMode:uniform|hotspot:N> <serverIp> <serverPort>"
            );
            System.exit(-1);
        }

        int searchesPerClient = Integer.parseInt(args[0]);
        int nClients = Integer.parseInt(args[1]);
        int bidExponent = Integer.parseInt(args[2]);
        int rootBucketSize = Integer.parseInt(args[3]);
        int competitionBucketSize = Integer.parseInt(args[4]);
        int bucketSize = Integer.parseInt(args[5]);
        int blockSize = Integer.parseInt(args[6]);
        Path datasetPath = Paths.get(args[7]);
        String queryMode = args[8];
        String serverIp = args[9];
        int serverPort = Integer.parseInt(args[10]);

        if (searchesPerClient <= 0 || nClients <= 0) {
            throw new IllegalArgumentException("searchesPerClient and nClients must be positive");
        }
        if (bidExponent <= 0 || bidExponent >= 31) {
            throw new IllegalArgumentException("bidExponent must be in [1, 30]");
        }

        int bidSpace = 1 << bidExponent;
        int leafCount = bidSpace;
        int treeHeight = bidExponent + 1;
        long sseSeed = Long.getLong("oram.sse.seed", 0x5EEDC0DEL);
        SSEIndex index = SSEIndexBuilder.build(datasetPath, blockSize, leafCount, sseSeed);
        if (index.getChunkCount() + nClients > bidSpace) {
            throw new IllegalArgumentException(
                    "Index and scratch blocks require " + (index.getChunkCount() + nClients) +
                            " bids, but bidSpace is " + bidSpace
            );
        }

        List<Integer> queryKeywords = selectQueryKeywords(index, queryMode);
        logger.info(
                "Loaded SSE index: keywords={}, chunks={}, documentsPerChunk={}, queryMode={}",
                index.getKeywordIds().size(),
                index.getChunkCount(),
                SSEChunkCodec.maxDocumentsPerChunk(blockSize),
                queryMode
        );

        ORAMManager.resetBandwidthStats();
        CountDownLatch readyLatch = new CountDownLatch(nClients);
        CountDownLatch startLatch = new CountDownLatch(1);
        Phaser stepPhaser = new Phaser(nClients);
        Phaser round2ReadPhaser = new Phaser(nClients);
        AtomicInteger finishedClients = new AtomicInteger();
        AtomicReference<Throwable> sharedFailure = new AtomicReference<>();

        SearchClient[] clients = new SearchClient[nClients];
        for (int slot = 0; slot < nClients; slot++) {
            clients[slot] = new SearchClient(
                    FIRST_CLIENT_ID + slot,
                    slot,
                    searchesPerClient,
                    nClients,
                    treeHeight,
                    rootBucketSize,
                    competitionBucketSize,
                    bucketSize,
                    blockSize,
                    bidSpace,
                    leafCount,
                    serverIp,
                    serverPort,
                    index,
                    queryKeywords,
                    readyLatch,
                    startLatch,
                    stepPhaser,
                    round2ReadPhaser,
                    finishedClients,
                    sharedFailure
            );
            clients[slot].start();
            Thread.sleep(10L);
        }

        readyLatch.await();
        logger.info("Executing concurrent SSE experiment");
        startLatch.countDown();

        for (SearchClient client : clients) {
            client.join();
        }

        long totalSearches = 0L;
        long totalChunkAccesses = 0L;
        long totalPaddingAccesses = 0L;
        long totalSearchLatencyNs = 0L;
        long earliestSearchStartNs = Long.MAX_VALUE;
        long latestSearchEndNs = Long.MIN_VALUE;
        for (SearchClient client : clients) {
            if (client.getFailure() != null) {
                throw new RuntimeException("SSE client failed", client.getFailure());
            }
            totalSearches += client.getCompletedSearches();
            totalChunkAccesses += client.getChunkAccesses();
            totalPaddingAccesses += client.getPaddingAccesses();
            totalSearchLatencyNs += client.getTotalSearchLatencyNs();
            earliestSearchStartNs = Math.min(earliestSearchStartNs, client.getFirstSearchStartNs());
            latestSearchEndNs = Math.max(latestSearchEndNs, client.getLastSearchEndNs());
        }

        double wallSeconds = totalSearches == 0L
                ? 0.0
                : (latestSearchEndNs - earliestSearchStartNs) / 1_000_000_000.0;
        double searchThroughput = totalSearches / wallSeconds;
        double chunkThroughput = totalChunkAccesses / wallSeconds;
        double averageSearchLatencyMs =
                totalSearches == 0L ? 0.0 : totalSearchLatencyNs / (double) totalSearches / 1_000_000.0;
        long protocolAccesses = totalChunkAccesses + totalPaddingAccesses;

        logger.info(String.format(
                "Concurrent SSE Benchmark Summary:%n" +
                        "\tConcurrent clients[#]: %d%n" +
                        "\tSearches per client[#]: %d%n" +
                        "\tCompleted searches[#]: %d%n" +
                        "\tUseful chunk accesses[#]: %d%n" +
                        "\tPadding accesses[#]: %d%n" +
                        "\tWall-clock time[s]: %.3f%n" +
                        "\tSearch throughput[searches/s]: %.3f%n" +
                        "\tChunk throughput[chunks/s]: %.3f%n" +
                        "\tAverage search latency[ms]: %.3f%n" +
                        "\tAverage protocol bandwidth/access[bytes]: %.3f",
                nClients,
                searchesPerClient,
                totalSearches,
                totalChunkAccesses,
                totalPaddingAccesses,
                wallSeconds,
                searchThroughput,
                chunkThroughput,
                averageSearchLatencyMs,
                protocolAccesses == 0L
                        ? 0.0
                        : ORAMManager.getTotalProtocolBytes() / (double) protocolAccesses
        ));
    }

    private static List<Integer> selectQueryKeywords(SSEIndex index, String queryMode) {
        List<Integer> keywords = index.getKeywordIds();
        if ("uniform".equalsIgnoreCase(queryMode)) {
            return keywords;
        }
        if (queryMode.startsWith("hotspot:")) {
            int count = Integer.parseInt(queryMode.substring("hotspot:".length()));
            if (count <= 0 || count > keywords.size()) {
                throw new IllegalArgumentException("Invalid hotspot size " + count);
            }
            return new ArrayList<>(keywords.subList(0, count));
        }
        throw new IllegalArgumentException("Unsupported query mode " + queryMode);
    }

    private static final class SearchClient extends Thread {
        private final int clientSlot;
        private final int targetSearches;
        private final int blockSize;
        private final ORAMManager manager;
        private final ClientAccessExecutor executor;
        private final SSEIndex index;
        private final List<Integer> queryKeywords;
        private final CountDownLatch readyLatch;
        private final CountDownLatch startLatch;
        private final Phaser stepPhaser;
        private final Phaser round2ReadPhaser;
        private final AtomicInteger finishedClients;
        private final AtomicReference<Throwable> sharedFailure;
        private final int totalClients;
        private final int scratchBid;
        private final Random random;
        private final SSEEntryMap entryMap;

        private int completedSearches;
        private long chunkAccesses;
        private long paddingAccesses;
        private long totalSearchLatencyNs;
        private long firstSearchStartNs = Long.MAX_VALUE;
        private long lastSearchEndNs = Long.MIN_VALUE;
        private Throwable failure;

        private int currentKeyword;
        private int currentBid;
        private int currentReadPid;
        private int currentWritePid;
        private int expectedChunkIndex;
        private List<Integer> currentDocuments;
        private long currentSearchStartNs;
        private DelayedRequest delayedRequest;
        private boolean targetReported;

        private SearchClient(int clientId,
                             int clientSlot,
                             int targetSearches,
                             int c,
                             int treeHeight,
                             int rootBucketSize,
                             int competitionBucketSize,
                             int bucketSize,
                             int blockSize,
                             int bidSpace,
                             int leafCount,
                             String serverIp,
                             int serverPort,
                             SSEIndex index,
                             List<Integer> queryKeywords,
                             CountDownLatch readyLatch,
                             CountDownLatch startLatch,
                             Phaser stepPhaser,
                             Phaser round2ReadPhaser,
                             AtomicInteger finishedClients,
                             AtomicReference<Throwable> sharedFailure) {
            this.clientSlot = clientSlot;
            this.targetSearches = targetSearches;
            this.blockSize = blockSize;
            this.manager = new ORAMManager(clientId, serverIp, serverPort);
            this.executor = new ClientAccessExecutor(
                    manager,
                    c,
                    leafCount,
                    treeHeight,
                    rootBucketSize,
                    competitionBucketSize,
                    bucketSize,
                    bidSpace,
                    blockSize,
                    round2ReadPhaser
            );
            this.index = index;
            this.queryKeywords = queryKeywords;
            this.readyLatch = readyLatch;
            this.startLatch = startLatch;
            this.stepPhaser = stepPhaser;
            this.round2ReadPhaser = round2ReadPhaser;
            this.finishedClients = finishedClients;
            this.sharedFailure = sharedFailure;
            this.totalClients = c;
            this.scratchBid = index.getChunkCount() + clientSlot;
            this.random = new Random(0xC0C05EEDL + clientId);
            this.entryMap = new SSEEntryMap();
            final java.util.Map<Integer, Integer> keywordByHeadBid = new java.util.HashMap<>();
            long seed = Long.getLong("oram.sse.seed", 0x5EEDC0DEL);
            for (int keywordId : index.getKeywordIds()) {
                int headBid = index.getHeadBid(keywordId);
                keywordByHeadBid.put(headBid, keywordId);
                entryMap.update(
                        keywordId,
                        SSEIndexBuilder.deterministicPid(headBid, leafCount, seed)
                );
            }
            executor.setPositionListener((bid, pid) -> {
                Integer keywordId = keywordByHeadBid.get(bid);
                if (keywordId != null) {
                    entryMap.update(keywordId, pid);
                }
            });
        }

        @Override
        public void run() {
            try {
                readyLatch.countDown();
                startLatch.await();
                beginNextSearch();

                while (true) {
                    if (sharedFailure.get() != null) {
                        break;
                    }
                    executeOneProtocolStep();
                    if (completedSearches >= targetSearches && !targetReported) {
                        targetReported = true;
                        finishedClients.incrementAndGet();
                    }

                    if (sharedFailure.get() != null) {
                        break;
                    }
                    int phase = stepPhaser.arriveAndAwaitAdvance();
                    if (phase < 0) {
                        break;
                    }
                    if (finishedClients.get() == totalClients) {
                        break;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failure = e;
            } catch (Throwable t) {
                failure = t;
                sharedFailure.compareAndSet(null, t);
                stepPhaser.forceTermination();
                round2ReadPhaser.forceTermination();
                t.printStackTrace();
            } finally {
                manager.close();
            }
        }

        private void executeOneProtocolStep() {
            if (completedSearches >= targetSearches) {
                executor.access(Operation.READ, scratchBid, new byte[blockSize], null);
                paddingAccesses++;
                return;
            }

            if (delayedRequest != null) {
                ClientAccessResult result = executor.access(
                        Operation.READ,
                        scratchBid,
                        new byte[blockSize],
                        delayedRequest
                );
                paddingAccesses++;
                byte[] delayedData = result.getDataPrev();
                delayedRequest = null;
                if (delayedData == null) {
                    throw new IllegalStateException(
                            "Delayed chunk was not available for keyword " + currentKeyword +
                                    ", bid=" + currentBid
                    );
                }
                consumeChunk(delayedData, -1, null, true);
                return;
            }

            ChunkTransition transition = new ChunkTransition();
            ClientAccessResult result = executor.access(
                    Operation.READ,
                    currentBid,
                    new byte[0],
                    null,
                    currentReadPid,
                    currentWritePid,
                    new ChunkPointerTransformer(transition)
            );
            chunkAccesses++;

            if (result.getDelayedRequest() != null) {
                delayedRequest = result.getDelayedRequest();
                return;
            }
            if (result.getDataCur() == null) {
                throw new IllegalStateException(
                        "Missing current chunk for keyword " + currentKeyword + ", bid=" + currentBid
                );
            }
            consumeChunk(result.getDataCur(), result.getUpdatedPid(), transition, false);
        }

        private void consumeChunk(byte[] encoded,
                                  int updatedPid,
                                  ChunkTransition transition,
                                  boolean fromDelayedResult) {
            SSEChunk chunk = SSEChunkCodec.decode(encoded);
            if (chunk.getKeywordId() != currentKeyword) {
                throw new IllegalStateException(
                        "Keyword mismatch: expected " + currentKeyword + ", got " + chunk.getKeywordId()
                );
            }
            if (chunk.getChunkIndex() != expectedChunkIndex) {
                throw new IllegalStateException(
                        "Chunk index mismatch for keyword " + currentKeyword +
                                ": expected " + expectedChunkIndex + ", got " + chunk.getChunkIndex()
                );
            }

            for (int documentId : chunk.getDocumentIds()) {
                currentDocuments.add(documentId);
            }
            if (chunk.getChunkIndex() == 0 && updatedPid >= 0) {
                entryMap.update(currentKeyword, updatedPid);
            }

            if (chunk.getNextBid() == SSEChunk.END_OF_LIST) {
                verifyCurrentSearch();
                long searchEndNs = System.nanoTime();
                completedSearches++;
                totalSearchLatencyNs += searchEndNs - currentSearchStartNs;
                lastSearchEndNs = Math.max(lastSearchEndNs, searchEndNs);
                currentReadPid = -1;
                currentWritePid = -1;
                if (completedSearches < targetSearches) {
                    beginNextSearch();
                }
                return;
            }

            currentBid = chunk.getNextBid();
            if (fromDelayedResult) {
                currentReadPid = chunk.getNextPid();
                currentWritePid = chunk.getNextPid();
            } else {
                if (transition == null || transition.oldNextPid < 0 || transition.newNextPid < 0) {
                    throw new IllegalStateException("Missing temporary chunk transition state");
                }
                currentReadPid = transition.oldNextPid;
                currentWritePid = transition.newNextPid;
            }
            expectedChunkIndex++;
        }

        private void beginNextSearch() {
            currentKeyword = queryKeywords.get(random.nextInt(queryKeywords.size()));
            currentBid = index.getHeadBid(currentKeyword);
            Integer headPid = entryMap.get(currentKeyword);
            if (headPid == null) {
                throw new IllegalStateException("Missing entry position for keyword " + currentKeyword);
            }
            currentReadPid = headPid;
            currentWritePid = executor.generateRandomPid();
            expectedChunkIndex = 0;
            currentDocuments = new ArrayList<>();
            currentSearchStartNs = System.nanoTime();
            firstSearchStartNs = Math.min(firstSearchStartNs, currentSearchStartNs);
            delayedRequest = null;
        }

        private final class ChunkPointerTransformer implements AccessDataTransformer {
            private final ChunkTransition transition;

            private ChunkPointerTransformer(ChunkTransition transition) {
                this.transition = transition;
            }

            @Override
            public byte[] transform(byte[] currentData) {
                SSEChunk chunk = SSEChunkCodec.decode(currentData);
                transition.oldNextPid = chunk.getNextPid();
                transition.newNextPid = chunk.getNextBid() == SSEChunk.END_OF_LIST
                        ? SSEChunk.END_OF_LIST
                        : executor.generateRandomPid();
                return SSEChunkCodec.encode(
                        new SSEChunk(
                                chunk.getKeywordId(),
                                chunk.getChunkIndex(),
                                chunk.getNextBid(),
                                transition.newNextPid,
                                chunk.getDocumentIds()
                        ),
                        blockSize
                );
            }
        }

        private static final class ChunkTransition {
            private int oldNextPid = SSEChunk.END_OF_LIST;
            private int newNextPid = SSEChunk.END_OF_LIST;
        }

        private void verifyCurrentSearch() {
            List<Integer> expected = index.getExpectedPostingList(currentKeyword);
            if (!expected.equals(currentDocuments)) {
                throw new IllegalStateException(
                        "Incorrect search result for keyword " + currentKeyword +
                                ": expected " + expected.size() +
                                " documents, got " + currentDocuments.size()
                );
            }
        }

        int getCompletedSearches() {
            return completedSearches;
        }

        long getChunkAccesses() {
            return chunkAccesses;
        }

        long getPaddingAccesses() {
            return paddingAccesses;
        }

        long getTotalSearchLatencyNs() {
            return totalSearchLatencyNs;
        }

        long getFirstSearchStartNs() {
            return firstSearchStartNs;
        }

        long getLastSearchEndNs() {
            return lastSearchEndNs;
        }

        Throwable getFailure() {
            return failure;
        }
    }
}
