package oram.client;

import comunication.Message;
import comunication.MessageProcessor;
import comunication.client.ClientCommunicationSystem;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class ServiceProxy extends MessageProcessor {
	private final int id;
	private final int messageType;
	private final ClientCommunicationSystem serviceProxy;
	private final Lock responseLock;
	private final Condition responseCondition;
	private final boolean directResponses;
	private Message response;

	public ServiceProxy(int id, int messageType, String serverIP, int serverPort) {
		super(messageType);
		this.id = id;
		this.messageType = messageType;
		this.responseLock = new ReentrantLock();
		this.responseCondition = responseLock.newCondition();
		this.directResponses = booleanProperty("oram.directClientResponses", true);
		try {
			this.serviceProxy = new ClientCommunicationSystem(
					id,
					1,
					100_000_000
			);
			serviceProxy.registerMessageListener(messageType, this);
			this.serviceProxy.connectTo(0, serverIP, serverPort);
			if (!directResponses) {
				start();
			}
		} catch (InterruptedException | IOException | NoSuchAlgorithmException | KeyStoreException |
				 CertificateException | UnrecoverableKeyException e) {
			throw new RuntimeException("Failed to connect to server", e);
		}
	}

	public Message sendMessage(byte[] serializedMessage) {
		try {
			responseLock.lock();
			Message m = new Message(id, messageType, serializedMessage);
			serviceProxy.sendMessage(0, m);
			responseCondition.await();
			return response;
		} catch (InterruptedException e) {
			return null;
		} finally {
			responseLock.unlock();
		}
	}

	@Override
	public void messageReceived(Message message) {
		if (directResponses) {
			// Each proxy permits one outstanding request, so the Netty callback can
			// signal the waiting caller directly without a delivery thread.
			deliverMessage(message);
		} else {
			super.messageReceived(message);
		}
	}

	private static boolean booleanProperty(String name, boolean defaultValue) {
		String value = System.getProperty(name);
		if (value == null) {
			return defaultValue;
		}
		return "1".equals(value) || Boolean.parseBoolean(value);
	}

	@Override
	public void deliverMessage(Message message) {
		try {
			responseLock.lock();
			response = message;
			responseCondition.signal();
		} finally {
			responseLock.unlock();
		}
	}

	public void close() {
		serviceProxy.shutdown();
		if (!directResponses) {
			interrupt();
		}
	}

	public int getProcessId() {
		return id;
	}
}
