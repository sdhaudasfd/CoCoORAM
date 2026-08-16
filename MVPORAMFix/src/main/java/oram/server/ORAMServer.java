package oram.server;

import oram.messages.*;
import oram.server.structure.*;
import oram.utils.ORAMContext;
import oram.utils.ORAMUtils;
import oram.utils.ServerOperationType;
import oram.utils.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class ORAMServer extends ServerExecutable {
	private final Logger logger = LoggerFactory.getLogger("oram");
	private final Logger measurementLogger = LoggerFactory.getLogger("measurement");
	private final TreeMap<Integer, ORAM> orams;
	private final TreeSet<Integer> senders;
	private int getPMCounter;
	private final ArrayList<Long> getPMLatencies;
	private int getPSCounter;
	private final ArrayList<Long> getPSLatencies;
	private long getPMBytesSent;
	private long getPSBytesSent;
	private long evictionBytesReceived;
	private int evictCounter;
	private final ArrayList<Long> evictionLatencies;
	private long lastPrint;
	private int nOutstandingTreeObjects;
	private final HashMap<Integer, EvictionORAMMessage> evictionRequests;
	private final Lock evictionLock = new ReentrantLock();
	private final Condition evictionCondition = evictionLock.newCondition();

	private final ArrayDeque<Integer> clientsQueue;
	private final Map<Integer, ORAMMessage> clientsRequests;
	private int activeClients;
	private final int MAX_N_CLIENTS;

	public ORAMServer(int max_clients, int processId, String ip, int port) throws InterruptedException {
		super(processId, ip, port);
		this.orams = new TreeMap<>();
		this.senders = new TreeSet<>();
		this.getPMLatencies = new ArrayList<>();
		this.getPSLatencies = new ArrayList<>();
		this.evictionLatencies = new ArrayList<>();
		this.evictionRequests = new HashMap<>();
		this.clientsRequests = new HashMap<>();
		this.clientsQueue = new ArrayDeque<>();
		this.MAX_N_CLIENTS = max_clients;
		logger.info("Ready to process operations");
	}

	public static void main(String[] args) throws InterruptedException {
		int maxClients = Integer.parseInt(args[0]);
		String ip = args[1];
		int port = Integer.parseInt(args[2]);
		int id = Integer.parseInt(args[3]);
		new ORAMServer(maxClients, id, ip, port);
	}

	@Override
	public byte[] execute(int sender, byte[] data) {
		ServerOperationType op;
		try {
			op = ServerOperationType.getOperation(data[0]);
			ORAMMessage request;
			senders.add(sender);
			int hash;
			switch (op) {
				case CREATE_ORAM:
					request = new CreateORAMMessage();
					request.readExternal(data, 1);
					return createORAM((CreateORAMMessage) request);
				case GET_ORAM:
					request = new ORAMMessage();
					request.readExternal(data, 1);
					return getORAM(request);
				case GET_POSITION_MAP:
					logger.debug("Received getPM request from {}", sender);
					request = new GetPathMaps();
					request.readExternal(data, 1);

					clientsQueue.addLast(sender);
					clientsRequests.put(sender, request);
					processQueuedGetPMRequests();
					return null;
				case GET_STASH_AND_PATH:
					logger.debug("Received getPS request from {}", sender);
					request = new StashPathORAMMessage();
					request.readExternal(data, 1);
					return getStashesAndPaths((StashPathORAMMessage) request, sender);
				case EVICTION_0:
					request = new EvictionORAMMessage();
					request.readExternal(data, 1);
					evictionLock.lock();
					hash = sender + ORAMUtils.computeHashCode(data) * 32;
					logger.debug("Received eviction data request from {} ({})", sender, hash);
					evictionRequests.put(hash, (EvictionORAMMessage) request);
					evictionCondition.signal();
					evictionLock.unlock();
					return new byte[]{(byte) Status.SUCCESS.ordinal()};
				case EVICTION:
					logger.debug("Received eviction request from {}", sender);
					evictionLock.lock();
					request = new ORAMMessage();
					request.readExternal(data, 1);
					hash = request.getOramId();

					do {
						logger.debug("Received eviction request from {} ({})", sender, hash);
						request = evictionRequests.get(hash);
						if (request == null) {
							logger.debug("No eviction request from {}", sender);
							try {
								evictionCondition.await();
							} catch (InterruptedException e) {
								logger.error("Interrupted while waiting for eviction request from {}", sender);
							}
						}
					} while (request == null);
					evictionLock.unlock();
					evictionBytesReceived += data.length;
					byte[] evictionResponse = performEviction((EvictionORAMMessage) request, sender);
					activeClients--;
					//process requests from the queue
					processQueuedGetPMRequests();
					return evictionResponse;
			}
		} finally {
			// printReport();
		}
		throw new RuntimeException("Unknown operation type");
	}

	private void processQueuedGetPMRequests() {
		while (activeClients < MAX_N_CLIENTS && !clientsQueue.isEmpty()) {
			activeClients++;
			Integer nextClientId = clientsQueue.poll();
			if (nextClientId == null) {
				logger.debug("No more requests in the queue");
				break;
			}
			logger.debug("Processing getPM request from queue from {} | active clients: {}", nextClientId,
					activeClients);
			ORAMMessage nextRequest = clientsRequests.remove(nextClientId);
			byte[] pmResponse = getPositionMap((GetPathMaps) nextRequest, nextClientId);
			if (pmResponse == null) {
				logger.error("Failed to process getPM request from {}", nextClientId);
				break;
			}

			sendResponse(nextClientId, pmResponse);
		}
	}

	private byte[] performEviction(EvictionORAMMessage request, int sender) {
		logger.debug("Processing eviction request from {}", sender);
		int oramId = request.getOramId();
		ORAM oram = orams.get(oramId);
		if (oram == null)
			return null;
		long start = System.nanoTime();
		boolean isEvicted = oram.performEviction(request.getEncryptedStash(), request.getEncryptedPathMap(),
				request.getEncryptedPath(), sender);
		long end = System.nanoTime();
		long delay = end - start;
		evictionLatencies.add(delay);
		//measurementLogger.debug("eviction[ns]: {}", delay);
		evictCounter++;

		nOutstandingTreeObjects = oram.getNOutstandingTreeObjects();
		if (isEvicted)
			return new byte[]{(byte) Status.SUCCESS.ordinal()};
		else
			return new byte[]{(byte) Status.FAILED.ordinal()};
	}

	private byte[] getStashesAndPaths(StashPathORAMMessage request, int clientId) {
		logger.debug("Processing getPS request from {}", clientId);
		int oramId = request.getOramId();
		ORAM oram = orams.get(oramId);
		if (oram == null) {
			return null;
		}
		long start = System.nanoTime();
		EncryptedStashesAndPaths encryptedStashesAndPaths = oram.getStashesAndPaths(request.getPathId(), clientId);
		byte[] serializedPathAndStash = null;
		if (encryptedStashesAndPaths != null) {
			int dataSize = encryptedStashesAndPaths.getSerializedSize();
			serializedPathAndStash = new byte[dataSize];
			int offset = encryptedStashesAndPaths.writeExternal(serializedPathAndStash, 0);
			if (offset != dataSize) {
				logger.error("Failed to serialize path and stash");
				return null;
			}
		}
		long end = System.nanoTime();
		long delay = end - start;
		getPSLatencies.add(delay);
		//measurementLogger.debug("getPathStash[ns]: {}", delay);
		getPSCounter++;

		if (serializedPathAndStash == null)
			return new byte[]{-1};
		getPSBytesSent += serializedPathAndStash.length;
		return serializedPathAndStash;
	}

	private byte[] getORAM(ORAMMessage request) {
		ORAM oram = orams.get(request.getOramId());
		if (oram == null) {
			return new byte[]{-1};
		} else {
			ORAMContext oramContext = oram.getOramContext();
			int treeHeight = oramContext.getTreeHeight();
			int nBlocksPerBucket = oramContext.getBucketSize();
			int blockSize = oramContext.getBlockSize();
			try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
				 DataOutputStream out = new DataOutputStream(bos)) {
				out.writeInt(treeHeight);
				out.writeInt(nBlocksPerBucket);
				out.writeInt(blockSize);
				out.flush();
				bos.flush();
				return bos.toByteArray();
			} catch (IOException e) {
				logger.error("Failed to serialize oram context: {}", e.getMessage());
				return new byte[]{-1};
			}
		}
	}

	private byte[] getPositionMap(GetPathMaps request, int sender) {
		logger.debug("Processing getPM request from {}", sender);
		int oramId = request.getOramId();
		ORAM oram = orams.get(oramId);
		if (oram == null)
			return null;
		long start = System.nanoTime();
		EncryptedPathMaps positionMaps = oram.getPositionMaps(sender, request);

		int dataSize = positionMaps.getSerializedSize();
		byte[] serializedPositionMaps = new byte[dataSize];

		int offset = positionMaps.writeExternal(serializedPositionMaps, 0);
		if (offset != dataSize) {
			logger.error("Failed to serialize position maps");
			return null;
		}
		long end = System.nanoTime();
		long delay = end - start;
		getPMLatencies.add(delay);
		//measurementLogger.debug("getPositionMap[ns]: {}", delay);
		getPMCounter++;
		getPMBytesSent += serializedPositionMaps.length;
		return serializedPositionMaps;
	}

	private byte[] createORAM(CreateORAMMessage request) {
		int oramId = request.getOramId();
		int treeHeight = request.getTreeHeight();
		int nBlocksPerBucket = request.getBucketSize();
		int blockSize = request.getBlockSize();
		EncryptedPathMap encryptedPathMap = request.getEncryptedPathMap();
		EncryptedStash encryptedStash = request.getEncryptedStash();
		if (orams.containsKey(oramId)) {
			logger.debug("ORAM with id {} exists", oramId);
			return new byte[]{(byte) Status.FAILED.ordinal()};
		} else {
			logger.debug("Created an ORAM with id {} of {} levels", oramId, treeHeight + 1);
			ORAM oram = new ORAM(oramId, treeHeight, nBlocksPerBucket, blockSize, encryptedPathMap, encryptedStash);
			orams.put(oramId, oram);
			return new byte[]{(byte) Status.SUCCESS.ordinal()};
		}
	}

	public void printReport() {
		long end = System.nanoTime();
		long delay = end - lastPrint;
		if (delay >= 2_000_000_000) {
			long getPMAvgLatency = computeAverage(getPMLatencies);
			long getPSAvgLatency = computeAverage(getPSLatencies);
			long evictionAvgLatency = computeAverage(evictionLatencies);
			long getPMBandwidth = (long)(getPMBytesSent / (delay / 1_000_000_000.0));
			long getPSBandwidth = (long)(getPSBytesSent / (delay / 1_000_000_000.0));
			long evictionBandwidth = (long)(evictionBytesReceived / (delay / 1_000_000_000.0));

			measurementLogger.info("M-clients: {}", senders.size());
			measurementLogger.info("M-delta: {}", delay);
			measurementLogger.info("M-getPMRequests: {}", getPMCounter);
			measurementLogger.info("M-getPMAvgLatency: {}", getPMAvgLatency);
			measurementLogger.info("M-getPMBandwidth: {}", getPMBandwidth);
			measurementLogger.info("M-getPSRequests: {}", getPSCounter);
			measurementLogger.info("M-getPSAvgLatency: {}", getPSAvgLatency);
			measurementLogger.info("M-getPSBandwidth: {}", getPSBandwidth);
			measurementLogger.info("M-evictionRequests: {}", evictCounter);
			measurementLogger.info("M-evictionAvgLatency: {}", evictionAvgLatency);
			measurementLogger.info("M-evictionBandwidth: {}", evictionBandwidth);
			measurementLogger.info("M-outstanding: {}", nOutstandingTreeObjects);

			//compute throughput
			double getPMThroughput = getPMCounter / (delay / 1_000_000_000.0);
			double getPSThroughput = getPSCounter / (delay / 1_000_000_000.0);
			double evictionThroughput = evictCounter / (delay / 1_000_000_000.0);

			logger.info("Throughput: {} getPM/s, {} getPS/s, {} eviction/s | maxClients: {}", getPMThroughput,
					getPSThroughput, evictionThroughput, MAX_N_CLIENTS);

			getPMCounter = 0;
			getPSCounter = 0;
			evictCounter = 0;
			getPMLatencies.clear();
			getPSLatencies.clear();
			evictionLatencies.clear();
			getPMBytesSent = 0;
			getPSBytesSent = 0;
			evictionBytesReceived = 0;
			lastPrint = end;
		}
	}

	private long computeAverage(ArrayList<Long> values) {
		if (values.isEmpty()) {
			return -1;
		}
		long sum = values.get(0);
		for (int i = 1; i < values.size(); i++) {
			sum += values.get(i);
		}
		return sum / values.size();
	}
}
