package oram.benchmark;

import oram.client.ConcurORAMManager;
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

public class ConcurORAMBenchmark {
    private static final Logger logger = LoggerFactory.getLogger("benchmarking");
    private static final int FIRST_CLIENT_ID = 1;

    public static void main(String[] args) throws InterruptedException {
        if (args.length != 10 && args.length != 11) {
            System.out.println("Usage: ... oram.benchmark.ConcurORAMBenchmark " +
                    "<nRequests> <nClients> <bidExponent> <stashSize> <Z> <S> <A> <blockSize> <server ip> <server port> [zipfParameter]");
            System.exit(-1);
        }

        int nRequests = Integer.parseInt(args[0]);
        int nClients = Integer.parseInt(args[1]);
        int bidExponent = Integer.parseInt(args[2]);
        int stashSize = Integer.parseInt(args[3]);
        int z = Integer.parseInt(args[4]);
        int s = Integer.parseInt(args[5]);
        int A = Integer.parseInt(args[6]);
        int blockSize = Integer.parseInt(args[7]);
        String serverIp = args[8];
        int serverPort = Integer.parseInt(args[9]);
        double zipfParameter = args.length == 11 ? Double.parseDouble(args[10]) : 0.0;
        if (zipfParameter < 0.0) {
            throw new IllegalArgumentException("zipfParameter must be >= 0 (0 means uniform)");
        }

        int treeHeight = bidExponent + 1;
        int bidSpace = 1 << bidExponent;
        CountDownLatch readyLatch = new CountDownLatch(nClients);
        CountDownLatch startLatch = new CountDownLatch(1);
        CyclicBarrier stepBarrier = new CyclicBarrier(nClients);

        Client[] clients = new Client[nClients];
        for (int i = 0; i < nClients; i++) {
            clients[i] = new Client(
                    FIRST_CLIENT_ID + i,
                    nRequests,
                    nClients,
                    treeHeight,
                    stashSize,
                    z,
                    s,
                    A,
                    blockSize,
                    bidSpace,
                    serverIp,
                    serverPort,
                    readyLatch,
                    startLatch,
                    stepBarrier,
                    zipfParameter
            );
            clients[i].start();
            Thread.sleep(10);
        }

        readyLatch.await();
        logger.info("Executing ConcurORAM experiment, bidDistribution={}",
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

        logger.info(String.format(
                "ConcurORAM Benchmark Summary:%n" +
                        "\tConcurrent clients[#]: %d%n" +
                        "\tRequests per client[#]: %d%n" +
                        "\tMeasured ops[#]: %d%n" +
                        "\tWall-clock time[s]: %.3f%n" +
                        "\tThroughput[ops/s]: %.3f",
                nClients,
                nRequests,
                totalOps,
                wallClockSeconds,
                throughputOpsPerSec
        ));
    }

    private static class Client extends Thread {
        private final int clientId;
        private final ConcurORAMManager manager;
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
                       int stashSize,
                       int z,
                       int s,
                       int A,
                       int blockSize,
                       int bidSpace,
                       String serverIp,
                       int serverPort,
                       CountDownLatch readyLatch,
                       CountDownLatch startLatch,
                       CyclicBarrier stepBarrier,
                       double zipfParameter) {
            this.clientId = clientId;
            this.nRequests = nRequests;
            this.bidSpace = bidSpace;
            this.blockSize = blockSize;
            this.readyLatch = readyLatch;
            this.startLatch = startLatch;
            this.stepBarrier = stepBarrier;
            this.random = new SecureRandom();
            this.zipfDistribution = zipfParameter == 0.0
                    ? null
                    : new ZipfDistribution(bidSpace, zipfParameter);
            this.manager = new ConcurORAMManager(
                    clientId,
                    serverIp,
                    serverPort,
                    c,
                    treeHeight,
                    stashSize,
                    z,
                    s,
                    A,
                    blockSize
            );
        }

        @Override
        public void run() {
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
                    manager.access(operation, bid, payload);
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

        long getCompletedOps() {
            return completedOps;
        }

        long getFirstStartNs() {
            return completedOps == 0 ? 0L : firstStartNs;
        }

        long getLastEndNs() {
            return completedOps == 0 ? 0L : lastEndNs;
        }

        Throwable getFailure() {
            return failure;
        }
    }

    private static byte[] buildPayload(int clientId, int opIndex, int bid, int blockSize) {
        byte[] payload = new byte[blockSize];
        Arrays.fill(payload, (byte) 0);
        byte[] text = ("c" + clientId + "-op" + opIndex + "-bid" + bid)
                .getBytes(StandardCharsets.UTF_8);
        System.arraycopy(text, 0, payload, 0, Math.min(text.length, payload.length));
        return payload;
    }
}
