package oram.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ORAMServer extends ServerExecutable {
    private static final Logger logger = LoggerFactory.getLogger("oram");

    private final ORAMService oramService;

    public ORAMServer(int c,
                      int bidExponent,
                      int rootBucketSize,
                      int competitionBucketSize,
                      int bucketSize,
                      int blockSize,
                      String ip,
                      int port) throws InterruptedException {
        super(0, ip, port);
        this.oramService = new ORAMService(c, bidExponent, rootBucketSize, competitionBucketSize, bucketSize, blockSize);
        logger.info("ORAM server ready");
    }

    public static void main(String[] args) throws InterruptedException {
        if (args.length != 8) {
            System.out.println("Usage: oram.server.ORAMServer " +
                    "<c> <bidExponent> <rootBucketSize> <competitionBucketSize> <bucketSize> <blockSize> <ip> <port>");
            System.exit(-1);
        }

        int c = Integer.parseInt(args[0]);
        int bidExponent = Integer.parseInt(args[1]);
        int rootBucketSize = Integer.parseInt(args[2]);
        int competitionBucketSize = Integer.parseInt(args[3]);
        int bucketSize = Integer.parseInt(args[4]);
        int blockSize = Integer.parseInt(args[5]);
        String ip = args[6];
        int port = Integer.parseInt(args[7]);

        new ORAMServer(c, bidExponent, rootBucketSize, competitionBucketSize, bucketSize, blockSize, ip, port);
    }

    @Override
    public byte[] execute(int sender, byte[] data) {
        return oramService.executeOrdered(sender, data);
    }

    public void shutdown() {
        serverCommunicationSystem.shutdown();
        interrupt();
    }
}
