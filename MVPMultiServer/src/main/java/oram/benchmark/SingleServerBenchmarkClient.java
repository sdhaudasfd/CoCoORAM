package oram.benchmark;

import oram.client.ORAMObject;
import oram.client.manager.ORAMManager;
import oram.single.client.SingleServerORAMManager;
import oram.utils.ORAMUtils;
import org.apache.commons.math3.distribution.ZipfDistribution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vss.facade.SecretSharingException;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;

public class SingleServerBenchmarkClient {
	private final static Logger logger = LoggerFactory.getLogger("benchmarking");
	public static void main(String[] args) throws SecretSharingException, InterruptedException {
		if (args.length != 10) {
			System.out.println("Usage: ... oram.benchmark.SingleServerBenchmarkClient <initialClientId> <nClients> " +
					"<nRequests> <treeHeight> <bucketSize> <blockSize> <zipf parameter> <server ip> <server port> " +
					"<isMeasurementLeader>");
			System.exit(-1);
		}

		int oramId = 1;
		int initialClientId = Integer.parseInt(args[0]);
		int nClients = Integer.parseInt(args[1]);
		int nRequests = Integer.parseInt(args[2]);
		int treeHeight = Integer.parseInt(args[3]);
		int bucketSize = Integer.parseInt(args[4]);
		int blockSize = Integer.parseInt(args[5]);
		double zipfParameter = Double.parseDouble(args[6]);
		String serverIp = args[7];
		int serverPort = Integer.parseInt(args[8]);
		boolean measurementLeader = Boolean.parseBoolean(args[9]);

		ORAMManager bootstrapManager = new SingleServerORAMManager(initialClientId, serverIp, serverPort);
		ORAMObject bootstrapORAM = bootstrapManager.createORAM(oramId, treeHeight, bucketSize, blockSize);
		if (bootstrapORAM == null) {
			bootstrapORAM = bootstrapManager.getORAM(oramId);
		}
		bootstrapManager.close();
		if (bootstrapORAM == null) {
			throw new IllegalStateException("Failed to initialize ORAM " + oramId);
		}

		CountDownLatch readyLatch = new CountDownLatch(nClients);
		CountDownLatch startLatch = new CountDownLatch(1);
		CountDownLatch doneLatch = new CountDownLatch(nClients);
		Client[] clients = new Client[nClients];
		for (int i = 0; i < nClients; i++) {
			clients[i] = new Client(oramId, initialClientId, initialClientId + i,
					treeHeight, bucketSize, blockSize, readyLatch, startLatch, doneLatch, nRequests, serverIp, serverPort, measurementLeader, zipfParameter);
			clients[i].start();
			Thread.sleep(10);
		}

		try {
			readyLatch.await();
			logger.info("Executing experiment");
			startLatch.countDown();
			doneLatch.await();
		} catch (InterruptedException e) {
			logger.error("Error while waiting for clients to start", e);
		}

		long completedOps = 0L;
		long earliestStartNs = Long.MAX_VALUE;
		long latestEndNs = Long.MIN_VALUE;
		long latencySumNs = 0L;
		for (Client client : clients) {
			completedOps += client.getCompletedOps();
			latencySumNs += client.getLatencySumNs();
			if (client.getCompletedOps() > 0) {
				earliestStartNs = Math.min(earliestStartNs, client.getStartNs());
				latestEndNs = Math.max(latestEndNs, client.getEndNs());
			}
		}
		double wallClockSeconds = completedOps == 0 ? 0.0 : (latestEndNs - earliestStartNs) / 1_000_000_000.0;
		double throughputOpsPerSec = wallClockSeconds == 0.0 ? 0.0 : completedOps / wallClockSeconds;
		double avgLatencyMs = completedOps == 0 ? 0.0 : latencySumNs / (double) completedOps / 1_000_000.0;

		logger.info(String.format(
				"SingleServer Benchmark Summary:%n" +
						"\tConcurrent clients[#]: %d%n" +
						"\tRequests per client[#]: %d%n" +
						"\tMeasured ops[#]: %d%n" +
						"\tWall-clock time[s]: %.3f%n" +
						"\tThroughput[ops/s]: %.3f%n" +
						"\tAverage latency[ms]: %.3f",
				nClients, nRequests, completedOps, wallClockSeconds, throughputOpsPerSec, avgLatencyMs));
		System.exit(0);
	}

	private static class Client extends Thread {
		private final Logger measurementLogger = LoggerFactory.getLogger("measurement");
		private final int initialClientId;
		private final ORAMManager oramManager;
		private final int clientId;
		private final CountDownLatch readyLatch;
		private final CountDownLatch startLatch;
		private final CountDownLatch doneLatch;
		private final int nRequests;
		private ORAMObject oram;
		private final byte[] blockContent;
		private int address;
		private final boolean measurementLeader;
		private final SecureRandom rndGenerator;
		private final ZipfDistribution zipfDistribution;
		private final int addressSpace;
		private long completedOps;
		private long startNs;
		private long endNs;
		private long latencySumNs;

		private Client(int oramId, int initialClientId, int clientId, int treeHeight, int bucketSize, int blockSize,
					   CountDownLatch readyLatch, CountDownLatch startLatch, CountDownLatch doneLatch,
					   int nRequests, String serverIp, int serverPort, boolean measurementLeader,
					   double zipfParameter) {
			this.initialClientId = initialClientId;
			this.oramManager = new SingleServerORAMManager(clientId, serverIp, serverPort);
			this.clientId = clientId;
			this.readyLatch = readyLatch;
			this.startLatch = startLatch;
			this.doneLatch = doneLatch;
			this.nRequests = nRequests;
			this.measurementLeader = measurementLeader;
			this.oram = oramManager.getORAM(oramId);
			if (oram == null) {
				throw new IllegalStateException("Client " + clientId + " failed to get ORAM " + oramId);
			}

			this.blockContent = new byte[blockSize];
			Arrays.fill(blockContent, (byte) 'a');
			this.addressSpace = 1 << treeHeight;
			this.address = clientId % addressSpace;
			this.rndGenerator = new SecureRandom();
			this.zipfDistribution = zipfParameter > 0.0
					? new ZipfDistribution(addressSpace, zipfParameter)
					: null;
		}

		@Override
		public void run() {
			try {
				readyLatch.countDown();
				startLatch.await();

				long t1, t2, delay;
				byte[] oldContent;
				boolean isWrite;
				for (int i = 0; i < nRequests; i++) {
					isWrite = rndGenerator.nextBoolean();
					address = zipfDistribution == null
							? rndGenerator.nextInt(addressSpace)
							: zipfDistribution.sample() - 1;
					t1 = System.nanoTime();

					if (isWrite) {
						oldContent = oram.writeMemory(address, blockContent);
					} else {
						oldContent = oram.readMemory(address);
					}
					t2 = System.nanoTime();
					delay = t2 - t1;
					if (completedOps == 0) {
						startNs = t1;
					}
					endNs = t2;
					completedOps++;
					latencySumNs += delay;
					/*if (!Arrays.equals(blockContent, oldContent)) {
						measurementLogger.error("[Client {}] Content at address {} is different ({})", clientId, address, Arrays.toString(oldContent));
						//break;
					}*/
					if (initialClientId == clientId && measurementLeader) {
						measurementLogger.info("M-global: {}", delay);
						logger.info("Access latency: {} ms", delay / 1_000_000.0);
					}
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} finally {
				oramManager.close();
				doneLatch.countDown();
			}
		}

		private long getCompletedOps() {
			return completedOps;
		}

		private long getStartNs() {
			return startNs;
		}

		private long getEndNs() {
			return endNs;
		}

		private long getLatencySumNs() {
			return latencySumNs;
		}
	}
}
