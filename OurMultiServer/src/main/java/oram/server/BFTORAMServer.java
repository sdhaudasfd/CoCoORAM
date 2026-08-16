package oram.server;

import bftsmart.tom.MessageContext;
import bftsmart.tom.ServiceReplica;
import confidential.ConfidentialMessage;
import confidential.facade.server.ConfidentialSingleExecutable;
import confidential.server.ConfidentialRecoverable;
import confidential.statemanagement.ConfidentialSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vss.secretsharing.VerifiableShare;

public class BFTORAMServer implements ConfidentialSingleExecutable {
    private static final Logger logger = LoggerFactory.getLogger("oram");

    private final ORAMService oramService;

    public BFTORAMServer(int processId,
                         int c,
                         int bidExponent,
                         int rootBucketSize,
                         int competitionBucketSize,
                         int bucketSize,
                         int blockSize) {
        this.oramService = new ORAMService(c, bidExponent, rootBucketSize, competitionBucketSize, bucketSize, blockSize);

        ConfidentialRecoverable recoverable = new ConfidentialRecoverable(processId, this);
        new ServiceReplica(processId, recoverable, recoverable, recoverable, recoverable);

        logger.info("BFT ORAM replica {} ready to process operations", processId);
    }

    public static void main(String[] args) {
        if (args.length != 7) {
            System.out.println("Usage: oram.server.BFTORAMServer " +
                    "<processId> <c> <bidExponent> <rootBucketSize> <competitionBucketSize> <bucketSize> <blockSize>");
            System.exit(-1);
        }

        int processId = Integer.parseInt(args[0]);
        int c = Integer.parseInt(args[1]);
        int bidExponent = Integer.parseInt(args[2]);
        int rootBucketSize = Integer.parseInt(args[3]);
        int competitionBucketSize = Integer.parseInt(args[4]);
        int bucketSize = Integer.parseInt(args[5]);
        int blockSize = Integer.parseInt(args[6]);

        new BFTORAMServer(processId, c, bidExponent, rootBucketSize, competitionBucketSize, bucketSize, blockSize);
    }

    @Override
    public ConfidentialMessage appExecuteOrdered(byte[] plainData, VerifiableShare[] shares, MessageContext msgCtx) {
        byte[] response = oramService.executeOrdered(msgCtx.getSender(), plainData);
        return response == null ? null : new ConfidentialMessage(response);
    }

    @Override
    public ConfidentialMessage appExecuteUnordered(byte[] plainData, VerifiableShare[] shares, MessageContext msgCtx) {
        byte[] response = oramService.executeUnordered(msgCtx.getSender(), plainData);
        return response == null ? null : new ConfidentialMessage(response);
    }

    @Override
    public ConfidentialSnapshot getConfidentialSnapshot() {
        return new ConfidentialSnapshot(new byte[0]);
    }

    @Override
    public void installConfidentialSnapshot(ConfidentialSnapshot confidentialSnapshot) {
    }
}


