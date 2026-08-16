package taostore.benchmark;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import taostore.client.TaoStoreClient;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;

public class TaoStoreBenchmarkClient {
    private final static Logger logger = LoggerFactory.getLogger("benchmarking");
    private static final int INITIAL_CLIENT_ID = 100;
    private static final boolean MEASUREMENT_LEADER = true;

    public static void main(String[] args) throws InterruptedException {
        if (args.length != 6) {
            System.out.println("Usage: taostore.benchmark.TaoStoreBenchmarkClient <nClients> <nRequests> <bidExponent> <blockSize> <proxyIp> <proxyPort>");
            System.exit(-1);
        }

        int nClients = Integer.parseInt(args[0]);
        int nRequests = Integer.parseInt(args[1]);
        int bidExponent = Integer.parseInt(args[2]);
        int blockSize = Integer.parseInt(args[3]);
        String proxyIp = args[4];
        int proxyPort = Integer.parseInt(args[5]);
        int addressSpace = 1 << bidExponent;

        CountDownLatch readyLatch = new CountDownLatch(nClients);
        CountDownLatch startSignal = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(nClients);
        Client[] clients = new Client[nClients];
        TaoStoreClient controlClient = new TaoStoreClient(INITIAL_CLIENT_ID + nClients + 1000, proxyIp, proxyPort);
        for (int i = 0; i < nClients; i++) {
            clients[i] = new Client(INITIAL_CLIENT_ID, INITIAL_CLIENT_ID + i, readyLatch, startSignal, doneLatch,
                    nRequests, proxyIp, proxyPort,
                    MEASUREMENT_LEADER, blockSize, addressSpace);
            clients[i].start();
            Thread.sleep(10);
        }

        readyLatch.await();
        logger.info("Executing experiment");
        startSignal.countDown();
        doneLatch.await();

        for (Client client : clients) {
            if (client.getFailure() != null) {
            throw new RuntimeException("Benchmark client failed", client.getFailure());
            }
        }

        long completedOps = 0L;
        long earliestStartNs = Long.MAX_VALUE;
        long latestEndNs = Long.MIN_VALUE;

        for (Client client : clients) {
            completedOps += client.getCompletedOps();
            if (client.getCompletedOps() > 0) {
                earliestStartNs = Math.min(earliestStartNs, client.getStartNs());
                latestEndNs = Math.max(latestEndNs, client.getEndNs());
            }
        }

        if (completedOps > 0) {
            controlClient.drain();
            latestEndNs = Math.max(latestEndNs, System.nanoTime());
        }
        controlClient.close();

        double wallClockSeconds = completedOps == 0 ? 0.0 : (latestEndNs - earliestStartNs) / 1_000_000_000.0;
        double throughputOpsPerSec = wallClockSeconds == 0.0 ? 0.0 : completedOps / wallClockSeconds;

        String summary = String.format(
            "TaoStore Benchmark Summary:%n" +
                "\tConcurrent clients[#]: %d%n" +
                "\tRequests per client[#]: %d%n" +
                "\tMeasured ops[#]: %d%n" +
                "\tWall-clock time[s]: %.3f%n" +
                "\tThroughput[ops/s]: %.3f",
            nClients,
            nRequests,
            completedOps,
            wallClockSeconds,
            throughputOpsPerSec
        );

        logger.info(summary);
        System.exit(0);
    }

    private static class Client extends Thread {
        private final int initialClientId;
        private final int clientId;
        private final CountDownLatch readyLatch;
        private final CountDownLatch startSignal;
        private final CountDownLatch doneLatch;
        private final int nRequests;
        private final TaoStoreClient taoStoreClient;
        private final byte[] blockContent;
        private final boolean measurementLeader;
        private final SecureRandom rnd;
        private final int addressSpace;
        private long completedOps;
        private long startNs;
        private long endNs;
        private Throwable failure;

        private Client(int initialClientId, int clientId,
                       CountDownLatch readyLatch,
                       CountDownLatch startSignal,
                       CountDownLatch doneLatch,
                       int nRequests,
                       String proxyIp, int proxyPort, boolean measurementLeader, int blockSize, int addressSpace) {
            this.initialClientId = initialClientId;
            this.clientId = clientId;
            this.readyLatch = readyLatch;
            this.startSignal = startSignal;
            this.doneLatch = doneLatch;
            this.nRequests = nRequests;
            this.taoStoreClient = new TaoStoreClient(clientId, proxyIp, proxyPort);
            this.measurementLeader = measurementLeader;
            this.blockContent = new byte[blockSize];
            Arrays.fill(blockContent, (byte) 'a');
            this.rnd = new SecureRandom();
            this.addressSpace = addressSpace;
            this.completedOps = 0L;
            this.startNs = 0L;
            this.endNs = 0L;
        }

        @Override
        public void run() {
            try {
                readyLatch.countDown();
                startSignal.await();
                startNs = System.nanoTime();
                for (int i = 0; i < nRequests; i++) {
                    boolean isWrite = rnd.nextBoolean();
                    int address = rnd.nextInt(addressSpace);

                    long t1 = System.nanoTime();
                    if (isWrite) {
                        taoStoreClient.writeMemory(address, blockContent);
                    } else {
                        taoStoreClient.readMemory(address);
                    }
                    completedOps++;
                }
                endNs = System.nanoTime();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failure = e;
            } catch (Throwable t) {
                failure = t;
                endNs = System.nanoTime();
            } finally {
                taoStoreClient.close();
                doneLatch.countDown();
            }
        }

        public long getCompletedOps() {
            return completedOps;
        }

        public long getStartNs() {
            return startNs;
        }

        public long getEndNs() {
            return endNs;
        }

        public Throwable getFailure() {
            return failure;
        }
    }
}
