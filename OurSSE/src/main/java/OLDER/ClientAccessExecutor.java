package oram.client;

import oram.security.ProtocolEncryptionManager;
import oram.structure.EncryptedGlobalMapEntry;
import oram.structure.EncryptedORAMPath;
import oram.structure.GlobalMapEntry;
import oram.structure.ORAMPath;
import oram.structure.UpdateMapEntry;
import oram.utils.Operation;

import java.util.HashMap;
import java.util.Map;

public class ClientAccessExecutor {
    private final ORAMManager manager;
    private final int c;
    private final int leafCount;
    private final int treeHeight;
    private final int bidSpace;
    private final int blockSize;
    private final Round1ContextDecoder round1Decoder;
    private final ClientRound2Helper round2Helper;
    private final EvictionPlanner evictionPlanner;
    private final ClientEvictionHelper evictionHelper;
    private final ProtocolEncryptionManager encryptionManager;
    private final Map<Integer, GlobalMapEntry> localGbMp;

    public ClientAccessExecutor(ORAMManager manager,
                                int c,
                                int leafCount,
                                int treeHeight,
                                int bidSpace,
                                int blockSize) {
        this.manager = manager;
        this.c = c;
        this.leafCount = leafCount;
        this.treeHeight = treeHeight;
        this.bidSpace = bidSpace;
        this.blockSize = blockSize;
        this.round1Decoder = new Round1ContextDecoder();
        this.round2Helper = new ClientRound2Helper(leafCount);
        this.evictionPlanner = new EvictionPlanner(leafCount, treeHeight, c, bidSpace);
        this.evictionHelper = new ClientEvictionHelper(treeHeight, blockSize, c, leafCount);
        this.encryptionManager = new ProtocolEncryptionManager();
        this.localGbMp = new HashMap<>();
        this.localGbMp.putAll(manager.initializeGbMp());
    }

    public ClientAccessResult access(Operation op,
                                     int bid,
                                     byte[] dataStar,
                                     DelayedRequest reqPrev) {
        // Submit (op, bid) and receive the response (seq, QL, LastRL, Empty_GbMp, LastUdMp)
        Round1Context context = round1Decoder.decode(manager.submitRound1Query(op, bid));
        applyLastUdMpToLocalGbMp(context.getLastUdMp());

        byte[] dataPrev = null;
        if (reqPrev != null) {
            dataPrev = round2Helper.readDelayed(reqPrev, context.getLastRL());
        }

        ReadDecision readDecision = round2Helper.decidePath(bid, context, localGbMp);
        ORAMPath pathCur = manager.readRound2Path(readDecision.getPid());
        ExtractCurResult extractCurResult = round2Helper.extractCur(bid, context.getSeq(), pathCur, context);

        EvictionAssignment assignment = evictionPlanner.plan(context.getSeq());
        ORAMPath pathE = manager.readRound2Path(assignment.getPidE());

        BuildUpdResult buildUpdResult = round2Helper.buildUpd(
                op,
                bid,
                dataStar,
                extractCurResult.getDataCur(),
                context.getSeq(),
                c,
                blockSize
        );

        manager.submitRound3UpdateCur(
                context.getSeq(),
                encryptionManager.encryptRLSlot(buildUpdResult.getSlotCur()),
                encryptionManager.encryptUpdateMapEntry(buildUpdResult.getMpCur())
        );

        BuildEvictionResult evictionResult = evictionHelper.buildEviction(
                pathE,
                context.getLastRL(),
                context.getLastUdMp(),
                localGbMp,
                assignment
        );

        EncryptedORAMPath encryptedPathPrime = encryptionManager.encryptPath(evictionResult.getPathPrime());
        EncryptedGlobalMapEntry[] encryptedMpPrime =
                encryptionManager.encryptGlobalMapEntries(
                        evictionResult.getMpPrime(),
                        assignment.getBidStartInclusive(),
                        assignment.getBidEndExclusive()
                );

        manager.submitRound3Eviction(
                encryptedPathPrime,
                assignment.getOwnedBucketIndexes(),
                assignment.getBidStartInclusive(),
                encryptedMpPrime
        );

        return new ClientAccessResult(
                dataPrev,
                extractCurResult.getDataCur(),
                extractCurResult.getDelayedRequest()
        );
    }

    private void applyLastUdMpToLocalGbMp(UpdateMapEntry[] lastUdMp) {
        for (UpdateMapEntry entry : lastUdMp) {
            if (entry == null || entry.isDummy()) {
                continue;
            }
            localGbMp.put(entry.getBid(), new GlobalMapEntry(entry.getPid(), entry.getSeq()));
        }
    }
}
