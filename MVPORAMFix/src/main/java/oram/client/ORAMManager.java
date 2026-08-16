package oram.client;

import comunication.Message;
import oram.client.structure.PathMap;
import oram.client.structure.Stash;
import oram.messages.CreateORAMMessage;
import oram.messages.ORAMMessage;
import oram.security.EncryptionManager;
import oram.server.structure.EncryptedPathMap;
import oram.server.structure.EncryptedStash;
import oram.utils.ORAMContext;
import oram.utils.ORAMUtils;
import oram.utils.ServerOperationType;
import oram.utils.Status;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;

public class ORAMManager {
	private final ServiceProxy serviceProxy;
	private final EncryptionManager encryptionManager;
	public final static int MESSAGE_TYPE = 1;

	public ORAMManager(int clientId, String serverIP, int serverPort) {
		this.encryptionManager = new EncryptionManager();
		this.serviceProxy = new ServiceProxy(clientId, MESSAGE_TYPE, serverIP, serverPort);
	}

	public ORAMObject createORAM(int oramId, int treeHeight, int bucketSize, int blockSize) {
		EncryptedPathMap encryptedPathMap = initializeEmptyPathMap();
		EncryptedStash encryptedStash = initializeEmptyStash(blockSize);
		CreateORAMMessage request = new CreateORAMMessage(oramId, treeHeight, bucketSize, blockSize,
				encryptedPathMap, encryptedStash);
		byte[] serializedRequest = ORAMUtils.serializeRequest(ServerOperationType.CREATE_ORAM, request);
		Message response = serviceProxy.sendMessage(serializedRequest);
		if (response == null || response.getSerializedMessage()== null) {
			return null;
		}

		Status status = Status.getStatus(response.getSerializedMessage()[0]);
		if (status == Status.FAILED) {
			return null;
		}
		ORAMContext oramContext = new ORAMContext(treeHeight, bucketSize, blockSize);
		return new ORAMObject(serviceProxy, oramId, oramContext, encryptionManager);
	}

	public ORAMObject getORAM(int oramId) {
		try {
			ORAMMessage request = new ORAMMessage(oramId);
			byte[] serializedRequest = ORAMUtils.serializeRequest(ServerOperationType.GET_ORAM, request);
			Message response = serviceProxy.sendMessage(serializedRequest);
			if (response == null || response.getSerializedMessage() == null) {
				return null;
			}

			try (ByteArrayInputStream bis = new ByteArrayInputStream(response.getSerializedMessage());
				 DataInputStream in = new DataInputStream(bis)) {
				int treeHeight = in.readInt();
				if (treeHeight == -1) {
					return null;
				}
				int bucketSize = in.readInt();
				int blockSize = in.readInt();
				ORAMContext oramContext = new ORAMContext(treeHeight, bucketSize, blockSize);
				return new ORAMObject(serviceProxy, oramId, oramContext, encryptionManager);
			}
		} catch (IOException e) {
			return null;
		}
	}

	private EncryptedStash initializeEmptyStash(int blockSize) {
		Stash stash = new Stash(blockSize);
		return encryptionManager.encryptStash(stash);
	}

	private EncryptedPathMap initializeEmptyPathMap() {
		PathMap pathMap = new PathMap(1);
		return encryptionManager.encryptPathMap(pathMap);
	}

	public void close() {
		serviceProxy.close();
	}

}
