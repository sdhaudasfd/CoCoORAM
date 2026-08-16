package opca.benchmark;

import opca.client.OpcaClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;

public class OpcaBenchmarkClient {
    private static final Logger logger = LoggerFactory.getLogger("benchmarking");
    private static final int INITIAL_CLIENT_ID = 100;
    private static final boolean MEASUREMENT_LEADER = true;

    public static void main(String[] args) throws InterruptedException {
        if (args.length != 7) {
            System.out.println("Usage: opca.benchmark.OpcaBenchmarkClient <nClients> <nRequests> <bidExponent> <bucketSize> <blockSize> <proxyIp> <proxyPort>");
            System.exit(-1);
        }

        int nClients = Integer.parseInt(args[0]);
        int nRequests = Integer.parseInt(args[1]);
        int bidExponent = Integer.parseInt(args[2]);
        int bucketSize = Integer.parseInt(args[3]);
        int blockSize = Integer.parseInt(args[4]);
        String proxyIp = args[5];
        int proxyPort = Integer.parseInt(args[6]);

        int addressSpace = 1 << bidExponent;

        CountDownLatch readyLatch = new CountDownLatch(nClients);
        CountDownLatch startSignal = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(nClients);
        Client[] clients = new Client[nClients];
        OpcaClient controlClient = new OpcaClient(INITIAL_CLIENT_ID + nClients + 1000, proxyIp, proxyPort);
        for (int i = 0; i < nClients; i++) {
            clients[i] = new Client(
                    INITIAL_CLIENT_ID,
                    INITIAL_CLIENT_ID + i,
                    nRequests,
                    blockSize,
                    proxyIp,
                    proxyPort,
                    readyLatch,
                    startSignal,
                    doneLatch,
                    MEASUREMENT_LEADER,
                    addressSpace
            );
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
            "Opca Benchmark Summary:%n" +
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
        logger.info("Opca benchmark finished");

        // Ensure CLI benchmark exits even if background communication threads linger.
        System.exit(0);
    }

    private static class Client extends Thread {
        private final int initialClientId;
        private final int clientId;
        private final int nRequests;
        private final OpcaClient client;
        private final CountDownLatch readyLatch;
        private final CountDownLatch startSignal;
        private final CountDownLatch doneLatch;
        private final boolean measurementLeader;
        private final SecureRandom random;
        private final int addressSpace;
        private final byte[] blockContent;
        private long completedOps;
        private long startNs;
        private long endNs;
        private Throwable failure;

        private Client(int initialClientId,
                       int clientId,
                       int nRequests,
                       int blockSize,
                       String proxyIp,
                       int proxyPort,
                       CountDownLatch readyLatch,
                       CountDownLatch startSignal,
                       CountDownLatch doneLatch,
                       boolean measurementLeader,
                       int addressSpace) {
            this.initialClientId = initialClientId;
            this.clientId = clientId;
            this.nRequests = nRequests;
            this.client = new OpcaClient(clientId, proxyIp, proxyPort);
            this.readyLatch = readyLatch;
            this.startSignal = startSignal;
            this.doneLatch = doneLatch;
            this.measurementLeader = measurementLeader;
            this.random = new SecureRandom();
            this.addressSpace = addressSpace;
            this.blockContent = new byte[blockSize];
            Arrays.fill(this.blockContent, (byte) 'a');
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
                    int address = random.nextInt(addressSpace);
                    boolean isWrite = random.nextBoolean();

                    long t1 = System.nanoTime();
                    if (isWrite) {
                        client.writeMemory(address, blockContent);
                    } else {
                        client.readMemory(address);
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
                client.close();
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
