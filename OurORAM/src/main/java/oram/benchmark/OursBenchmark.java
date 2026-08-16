package oram.benchmark;

import oram.client.ClientAccessExecutor;
import oram.client.ORAMManager;
import oram.client.module.ClientAccessResult;
import oram.client.module.DelayedRequest;
import oram.utils.Operation;
import org.apache.commons.math3.distribution.ZipfDistribution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Phaser;

public class OursBenchmark {
    private static final Logger logger = LoggerFactory.getLogger("benchmarking");
    private static final int FIRST_CLIENT_ID = 1;

    public static void main(String[] args) throws InterruptedException {
        if (args.length != 9 && args.length != 10) {
            System.out.println("Usage: ... oram.benchmark.OursBenchmark " +
                    "<nRequests> <nClients> <bidExponent> <rootBucketSize> " +
                    "<competitionBucketSize> <bucketSize> <blockSize> <server ip> <server port> [zipfParameter]");
            System.exit(-1);
        }

        int nRequests = Integer.parseInt(args[0]);
        int nClients = Integer.parseInt(args[1]);
        int bidExponent = Integer.parseInt(args[2]);
        int rootBucketSize = Integer.parseInt(args[3]);
        int competitionBucketSize = Integer.parseInt(args[4]);
        int bucketSize = Integer.parseInt(args[5]);
        int blockSize = Integer.parseInt(args[6]);
        String serverIp = args[7];
        int serverPort = Integer.parseInt(args[8]);
        double zipfParameter = args.length == 10 ? Double.parseDouble(args[9]) : 0.0;
        if (zipfParameter < 0.0) {
            throw new IllegalArgumentException("zipfParameter must be >= 0 (0 means uniform)");
        }

        if (bidExponent <= 0 || bidExponent >= 31) {
            throw new IllegalArgumentException("bidExponent must be in [1, 30]");
        }

        int c = nClients;
        int bidSpace = 1 << bidExponent;
        int leafCount = bidSpace;
        int treeHeight = bidExponent + 1;

        ORAMManager.resetBandwidthStats();

        CountDownLatch readyLatch = new CountDownLatch(nClients);
        CountDownLatch startLatch = new CountDownLatch(1);
        CyclicBarrier stepBarrier = new CyclicBarrier(nClients);
        Phaser round2ReadPhaser = new Phaser(nClients);

        Client[] clients = new Client[nClients];
        for (int i = 0; i < nClients; i++) {
            clients[i] = new Client(
                    FIRST_CLIENT_ID + i,
                    nRequests,
                    c,
                    treeHeight,
                    rootBucketSize,
                    competitionBucketSize,
                    bucketSize,
                    blockSize,
                    bidSpace,
                    leafCount,
                    serverIp,
                    serverPort,
                    readyLatch,
                    startLatch,
                    stepBarrier,
                    round2ReadPhaser,
                    zipfParameter
            );
            clients[i].start();
            Thread.sleep(10);
        }

        readyLatch.await();
        logger.info("Executing experiment, bidDistribution={}",
                zipfParameter == 0.0 ? "uniform" : "zipf(" + zipfParameter + ")");
        startLatch.countDown();

        for (Client client : clients) {
            client.join();
        }

        for (Client client : clients) {
            if (client.getFailure() != null) {
                throw new RuntimeException("Benchmark client failed", client.getFailure());
            }
        }

        long totalOps = 0;
        long earliestStartNs = Long.MAX_VALUE;
        long latestEndNs = Long.MIN_VALUE;

        for (Client client : clients) {
            totalOps += client.getCompletedOps();
            earliestStartNs = Math.min(earliestStartNs, client.getFirstStartNs());
            latestEndNs = Math.max(latestEndNs, client.getLastEndNs());
        }

        double wallClockSeconds =
                totalOps == 0 ? 0.0 : (latestEndNs - earliestStartNs) / 1_000_000_000.0;

        double throughputOpsPerSec =
                wallClockSeconds == 0.0 ? 0.0 : totalOps / wallClockSeconds;

        long clientResidentStorageBytesPerClient =
                nClients == 0 ? 0L : clients[0].getResidentClientStorageBytes();

        double avgRound1BandwidthBytesPerAccess =
                totalOps == 0 ? 0.0 : ((double) ORAMManager.getRound1TotalBytes()) / totalOps;
        double avgRound2BandwidthBytesPerAccess =
                totalOps == 0 ? 0.0 : ((double) ORAMManager.getRound2TotalBytes()) / totalOps;
        double avgRound3BandwidthBytesPerAccess =
                totalOps == 0 ? 0.0 : ((double) ORAMManager.getRound3TotalBytes()) / totalOps;
        double avgTotalBandwidthBytesPerAccess =
                totalOps == 0 ? 0.0 : ((double) ORAMManager.getTotalProtocolBytes()) / totalOps;

        logger.info(String.format(
                "Ours Benchmark Summary:%n" +
                        "\tConcurrent clients[#]: %d%n" +
                        "\tRequests per client[#]: %d%n" +
                        "\tMeasured ops[#]: %d%n" +
                        "\tWall-clock time[s]: %.3f%n" +
                        "\tThroughput[ops/s]: %.3f%n" +
                        "\tClient resident storage per client[bytes]: %d%n" +
                        "\tAvg Round1 bandwidth per access[bytes]: %.3f%n" +
                        "\tAvg Round2 bandwidth per access[bytes]: %.3f%n" +
                        "\tAvg Round3 bandwidth per access[bytes]: %.3f%n" +
                        "\tAvg Total bandwidth per access[bytes]: %.3f",
                nClients,
                nRequests,
                totalOps,
                wallClockSeconds,
                throughputOpsPerSec,
                clientResidentStorageBytesPerClient,
                avgRound1BandwidthBytesPerAccess,
                avgRound2BandwidthBytesPerAccess,
                avgRound3BandwidthBytesPerAccess,
                avgTotalBandwidthBytesPerAccess
        ));
    }

    private static class Client extends Thread {
        private final int clientId;
        private final ORAMManager manager;
        private final ClientAccessExecutor accessExecutor;
        private final int nRequests;
        private final int bidSpace;
        private final int blockSize;
        private final CountDownLatch readyLatch;
        private final CountDownLatch startLatch;
        private final CyclicBarrier stepBarrier;
        private final SecureRandom random;
        private final ZipfDistribution zipfDistribution;

        private long completedOps;
        private long firstStartNs = Long.MAX_VALUE;
        private long lastEndNs = Long.MIN_VALUE;
        private Throwable failure;

        private Client(int clientId,
                       int nRequests,
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
                       CountDownLatch readyLatch,
                       CountDownLatch startLatch,
                       CyclicBarrier stepBarrier,
                       Phaser round2ReadPhaser,
                       double zipfParameter) {
            this.clientId = clientId;
            this.nRequests = nRequests;
            this.bidSpace = bidSpace;
            this.blockSize = blockSize;
            this.readyLatch = readyLatch;
            this.startLatch = startLatch;
            this.stepBarrier = stepBarrier;
            this.manager = new ORAMManager(clientId, serverIp, serverPort);
            this.accessExecutor = new ClientAccessExecutor(
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
            this.random = new SecureRandom();
            this.zipfDistribution = zipfParameter == 0.0
                    ? null
                    : new ZipfDistribution(bidSpace, zipfParameter);
            this.completedOps = 0L;
            this.firstStartNs = Long.MAX_VALUE;
            this.lastEndNs = Long.MIN_VALUE;
        }

        @Override
        public void run() {
            DelayedRequest delayedRequest = null;

            try {
                readyLatch.countDown();
                startLatch.await();

                for (int i = 0; i < nRequests; i++) {
                    int bid = zipfDistribution == null
                            ? random.nextInt(bidSpace)
                            : zipfDistribution.sample() - 1;
                    Operation operation = random.nextBoolean() ? Operation.WRITE : Operation.READ;
                    byte[] payload = buildPayload(clientId, i, bid, blockSize);

                    long t1 = System.nanoTime();
                    firstStartNs = Math.min(firstStartNs, t1);

                    ClientAccessResult result = accessExecutor.access(
                            operation,
                            bid,
                            payload,
                            delayedRequest
                    );
                    delayedRequest = result.getDelayedRequest();

                    stepBarrier.await();

                    long t2 = System.nanoTime();
                    lastEndNs = Math.max(lastEndNs, t2);
                    completedOps++;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failure = e;
            } catch (BrokenBarrierException e) {
                failure = e;
            } catch (Throwable t) {
                failure = t;
                t.printStackTrace();
            } finally {
                manager.close();
            }
        }

        public long getCompletedOps() {
            return completedOps;
        }

        public long getFirstStartNs() {
            return completedOps == 0 ? 0L : firstStartNs;
        }

        public long getLastEndNs() {
            return completedOps == 0 ? 0L : lastEndNs;
        }

        public Throwable getFailure() {
            return failure;
        }

        public long getResidentClientStorageBytes() {
            return accessExecutor.getResidentClientStorageBytes();
        }
    }

    private static byte[] buildPayload(int clientId, int opIndex, int bid, int blockSize) {
        byte[] payload = new byte[blockSize];
        Arrays.fill(payload, (byte) 0);

        byte[] text = ("c" + clientId + "-op" + opIndex + "-bid" + bid)
                .getBytes(StandardCharsets.UTF_8);
        int length = Math.min(text.length, payload.length);
        System.arraycopy(text, 0, payload, 0, length);
        return payload;
    }
}
