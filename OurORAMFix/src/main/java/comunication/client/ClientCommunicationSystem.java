package comunication.client;

import comunication.CommunicationSystem;
import comunication.util.BasicChannelInitializer;
import comunication.util.ChannelSession;
import comunication.util.SSLChannelInitializer;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.util.concurrent.Future;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.*;
import java.security.cert.CertificateException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;

public class ClientCommunicationSystem extends CommunicationSystem {
	private static final Object SHARED_WORKER_LOCK = new Object();
	private static EventLoopGroup sharedWorkerGroup;
	private static int sharedWorkerUsers;

	private final Bootstrap bootstrap;
	private final EventLoopGroup workerGroup;
	private final boolean usesSharedWorkerGroup;

	public ClientCommunicationSystem(int myId, int nWorkerThreads, int maxMessageSize) throws IOException,
			NoSuchAlgorithmException, KeyStoreException, CertificateException, UnrecoverableKeyException {
		this(myId, nWorkerThreads, maxMessageSize, booleanProperty("oram.sharedClientIo", true));
	}

	private static boolean booleanProperty(String name, boolean defaultValue) {
		String value = System.getProperty(name);
		if (value == null) {
			return defaultValue;
		}
		return "1".equals(value) || Boolean.parseBoolean(value);
	}

	protected ClientCommunicationSystem(int myId,
			int nWorkerThreads,
			int maxMessageSize,
			boolean useSharedWorkerGroup) throws IOException, NoSuchAlgorithmException, KeyStoreException,
			CertificateException, UnrecoverableKeyException {
		super(myId);
		usesSharedWorkerGroup = useSharedWorkerGroup;
		workerGroup = useSharedWorkerGroup
				? acquireSharedWorkerGroup()
				: new NioEventLoopGroup(nWorkerThreads);

		ChannelInitializer<Channel> channelInitializer = new BasicChannelInitializer(this,
				maxMessageSize, this);
		bootstrap = new Bootstrap();
		bootstrap.group(workerGroup)
				.channel(NioSocketChannel.class)
				.option(ChannelOption.SO_KEEPALIVE, true)
				.option(ChannelOption.TCP_NODELAY, true)
				.option(ChannelOption.SO_SNDBUF, tcpSendBufferSize)
				.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectionTimeoutMsec)
				.handler(channelInitializer);
	}

	private static EventLoopGroup acquireSharedWorkerGroup() {
		synchronized (SHARED_WORKER_LOCK) {
			if (sharedWorkerGroup == null) {
				int defaultThreads = Math.max(2, Math.min(16, Runtime.getRuntime().availableProcessors()));
				int configuredThreads = Integer.getInteger("oram.clientIoThreads", defaultThreads);
				if (configuredThreads <= 0) {
					throw new IllegalArgumentException("oram.clientIoThreads must be positive");
				}
				sharedWorkerGroup = new NioEventLoopGroup(configuredThreads);
			}
			sharedWorkerUsers++;
			return sharedWorkerGroup;
		}
	}

	private static EventLoopGroup releaseSharedWorkerGroup() {
		synchronized (SHARED_WORKER_LOCK) {
			if (sharedWorkerUsers <= 0) {
				return null;
			}
			sharedWorkerUsers--;
			if (sharedWorkerUsers != 0) {
				return null;
			}
			EventLoopGroup group = sharedWorkerGroup;
			sharedWorkerGroup = null;
			return group;
		}
	}

	private ChannelInitializer<Channel> createChannelInitializer(int maxMessageSize) throws NoSuchAlgorithmException,
			KeyStoreException, IOException, CertificateException, UnrecoverableKeyException {
		String algorithm = Security.getProperty("ssl.KeyManagerFactory.algorithm");
		String sslTLSKeyStore = "EC_KeyPair_256.pkcs12";

		KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
		try (FileInputStream fis = new FileInputStream("config/keysSSL_TLS/" + sslTLSKeyStore)) {
			ks.load(fis, SECRET.toCharArray());
		}

		KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(algorithm);
		keyManagerFactory.init(ks, SECRET.toCharArray());

		TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(algorithm);
		trustManagerFactory.init(ks);

		Iterable<String> cipher = Collections.singletonList("TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256");
		SslContext sslContext = SslContextBuilder.forClient()
				//.sslProvider(SslProvider.OPENSSL)
				.trustManager(trustManagerFactory)
				.keyManager(keyManagerFactory)
				.ciphers(cipher)
				.build();

		return new SSLChannelInitializer(sslContext, this, maxMessageSize, this);
	}

	public void connectTo(int serverId, String ipAddress, int port) throws InterruptedException {
		ChannelFuture f = bootstrap.connect(ipAddress, port);
		f.addListener(future -> {
			if (future.isSuccess()) {
				logger.debug("Connected to " + ipAddress + ":" + port);
			} else {
				logger.error("Connection to " + ipAddress + ":" + port + " failed.");
			}
		});

		f.awaitUninterruptibly();

		if (f.isSuccess()) {
			ChannelSession channelSession = new ChannelSession(serverId, f.channel());
			newSession(channelSession);
		}
	}

	@Override
	public void shutdown() {
		super.shutdown();
		EventLoopGroup groupToShutdown = usesSharedWorkerGroup
				? releaseSharedWorkerGroup()
				: workerGroup;
		if (groupToShutdown != null) {
			Future<?> future = groupToShutdown.shutdownGracefully();
			future.syncUninterruptibly();
		}
	}
}
