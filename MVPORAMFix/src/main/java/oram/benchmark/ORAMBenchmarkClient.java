package oram.benchmark;

import oram.client.ORAMManager;
import oram.client.ORAMObject;
import oram.utils.ORAMUtils;
import org.apache.commons.math3.distribution.ZipfDistribution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;

public class ORAMBenchmarkClient {
	private final static Logger logger = LoggerFactory.getLogger("benchmarking");
	public static void main(String[] args) throws InterruptedException {
		if (args.length != 10) {
			System.out.println("Usage: ... oram.benchmark.ORAMBenchmarkClient <initialClientId> <nClients> " +
					"<nRequests> <treeHeight> <bucketSize> <blockSize> <zipf parameter> " +
					"<server ip> <server port> <isMeasurementLeader>");
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

		CountDownLatch readyLatch = new CountDownLatch(nClients);
		CountDownLatch startLatch = new CountDownLatch(1);
		logger.info("Bid distribution: {}", zipfParameter == 0.0
				? "uniform" : "zipf(" + zipfParameter + ")");
		Client[] clients = new Client[nClients];
		for (int i = 0; i < nClients; i++) {
			clients[i] = new Client(oramId, initialClientId, initialClientId + i, treeHeight, bucketSize,
					blockSize, readyLatch, startLatch, nRequests, serverIp, serverPort, measurementLeader, zipfParameter);
			clients[i].start();
			Thread.sleep(10);
		}

		try {
			readyLatch.await();
			logger.info("Executing experiment");
			startLatch.countDown();
			for (Client client : clients) {
				client.join();
			}
		} catch (InterruptedException e) {
			logger.error("Error while waiting for clients to start", e);
			Thread.currentThread().interrupt();
			return;
		}

		for (Client client : clients) {
			if (client.getFailure() != null) {
				throw new RuntimeException("Benchmark client failed", client.getFailure());
			}
		}

		long totalOps = 0L;
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
				"MVP-ORAM Benchmark Summary:%n" +
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
		private final int initialClientId;
		private final ORAMManager oramManager;
		private final int clientId;
		private final CountDownLatch readyLatch;
		private final CountDownLatch startLatch;
		private final int nRequests;
		private ORAMObject oram;
		private final byte[] blockContent;
		private final boolean measurementLeader;
		private final SecureRandom rndGenerator;
		private final ZipfDistribution zipfDistribution;
		private final int addressSpace;
		private long completedOps;
		private long firstStartNs;
		private long lastEndNs;
		private Throwable failure;

		private Client(int oramId, int initialClientId, int clientId, int treeHeight, int bucketSize, int blockSize,
					   CountDownLatch readyLatch, CountDownLatch startLatch, int nRequests, String serverIp, int serverPort, boolean measurementLeader,
					   double zipfParameter) {
			this.initialClientId = initialClientId;
			this.oramManager = new ORAMManager(clientId, serverIp, serverPort);
			this.clientId = clientId;
			this.readyLatch = readyLatch;
			this.startLatch = startLatch;
			this.nRequests = nRequests;
			this.measurementLeader = measurementLeader;
			this.oram = oramManager.createORAM(oramId, treeHeight, bucketSize, blockSize);
			if (oram == null) {
				oram = oramManager.getORAM(oramId);
			}

			if (initialClientId == clientId && measurementLeader) {
				oram.measure();
			}

			this.blockContent = new byte[blockSize];
			Arrays.fill(blockContent, (byte) 'a');
			this.addressSpace = 1 << treeHeight;
			this.rndGenerator = new SecureRandom();
			if (zipfParameter < 0.0) {
				throw new IllegalArgumentException("zipfParameter must be >= 0 (0 means uniform)");
			}
			this.zipfDistribution = zipfParameter == 0.0
					? null
					: new ZipfDistribution(addressSpace, zipfParameter);
			this.completedOps = 0L;
			this.firstStartNs = Long.MAX_VALUE;
			this.lastEndNs = Long.MIN_VALUE;
		}

		@Override
		public void run() {
			try {
				readyLatch.countDown();
				startLatch.await();
				long t1, t2;
				byte[] oldContent;
				boolean isWrite;
				int address;
				for (int i = 0; i < nRequests; i++) {
					isWrite = rndGenerator.nextBoolean();
					address = zipfDistribution == null
							? rndGenerator.nextInt(addressSpace)
							: zipfDistribution.sample() - 1;

					t1 = System.nanoTime();
					firstStartNs = Math.min(firstStartNs, t1);

					if (isWrite) {
						oldContent = oram.writeMemory(address, blockContent);
					} else {
						oldContent = oram.readMemory(address);
					}

					t2 = System.nanoTime();
					lastEndNs = Math.max(lastEndNs, t2);

					/*if (!Arrays.equals(blockContent, oldContent)) {
						logger.error("[Client {}] Content at address {} is different ({})", clientId, address, Arrays.toString(oldContent));
						//break;
					}*/
					completedOps++;
				}
			} catch (Throwable t) {
				failure = t;
			} finally {
				oramManager.close();
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
	}
}
