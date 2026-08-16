package oram.client;

import oram.client.module.DelayedRequest;
import oram.client.module.Round1Context;
import oram.client.module.Round1RequestItem;
import oram.structure.GlobalMapEntry;
import oram.structure.RLSlot;
import oram.structure.UpdateMapEntry;
import oram.utils.Operation;

import java.security.SecureRandom;
import java.util.List;

public class ClientRound2Helper {
    private final int leafCount;
    private final int blockSize;
    private final SecureRandom random;

    public ClientRound2Helper(int leafCount, int blockSize) {
        this.leafCount = leafCount;
        this.blockSize = blockSize;
        this.random = new SecureRandom();
    }

    public byte[] readDelayed(DelayedRequest reqPrev, RLSlot[] lastRL) {
        if (reqPrev == null) {
            return null;
        }

        RLSlot best = null;
        for (RLSlot slot : lastRL) {
            if (slot == null || slot.isDummy()) {
                continue;
            }
            if (slot.getBid() != reqPrev.getBid()) {
                continue;
            }
            if (slot.getSeq() >= reqPrev.getSeq()) {
                continue;
            }
            if (best == null || slot.getSeq() > best.getSeq()) {
                best = slot;
            }
        }

        if (best == null) {
            return null;
        }

        return best.getData();
    }

    public ReadDecision decidePath(int bid, Round1Context context, GlobalMapEntry[] gbMp) {
        boolean inLastRL = containsBidInLastRL(context.getLastRL(), bid);
        boolean inQL = containsBidInQL(context.getPriorRequests(), bid);

        if (!inLastRL && !inQL) {
            if (bid < 0 || bid >= gbMp.length) {
                throw new IllegalStateException("Invalid bid " + bid + " for local GbMp length " + gbMp.length);
            }
            GlobalMapEntry entry = gbMp[bid];
            if (entry == null) {
                throw new IllegalStateException("Missing GbMp entry for bid " + bid);
            }
            return new ReadDecision(entry.getPid(), false);
        }

        return new ReadDecision(generateRandomPid(), true);
    }

    public boolean containsWriteForBidInQL(Round1Context context, int bid) {
        for (Round1RequestItem item : context.getPriorRequests()) {
            if (item.getBid() == bid && item.getOperation() == Operation.WRITE) {
                return true;
            }
        }
        return false;
    }

    private boolean containsBidInLastRL(RLSlot[] lastRL, int bid) {
        for (RLSlot slot : lastRL) {
            if (slot == null || slot.isDummy()) {
                continue;
            }
            if (slot.getBid() == bid) {
                return true;
            }
        }
        return false;
    }

    private boolean containsBidInQL(List<Round1RequestItem> priorRequests, int bid) {
        for (Round1RequestItem item : priorRequests) {
            if (item.getBid() == bid) {
                return true;
            }
        }
        return false;
    }

    private int generateRandomPid() {
        return random.nextInt(leafCount);
    }

    public ExtractCurResult extractCur(int bid, int seq, oram.structure.ORAMPath pathCur, Round1Context context) {
        boolean inLastRL = containsBidInLastRL(context.getLastRL(), bid);
        boolean hasWriteInQL = containsWriteForBidInQL(context, bid);
        boolean inQL = containsBidInQL(context.getPriorRequests(), bid);

        if (inLastRL && !hasWriteInQL) {
            byte[] dataCur = findClosestPreviousDataInLastRL(context.getLastRL(), bid, seq);
            return new ExtractCurResult(dataCur, null);
        }

        if (!inQL) {
            oram.structure.ORAMBlock block = pathCur.findLatestBlock(bid);
            if (block == null) {
                PerformanceOnlyStats.recordMissingBlock();
                return new ExtractCurResult(new byte[blockSize], null);
            }
            return new ExtractCurResult(block.getData(), null);
        }

        return new ExtractCurResult(null, new DelayedRequest(bid, seq));
    }

    private byte[] findClosestPreviousDataInLastRL(RLSlot[] lastRL, int bid, int seq) {
        RLSlot best = null;

        for (RLSlot slot : lastRL) {
            if (slot == null || slot.isDummy()) {
                continue;
            }
            if (slot.getBid() != bid) {
                continue;
            }
            if (slot.getSeq() >= seq) {
                continue;
            }
            if (best == null || slot.getSeq() > best.getSeq()) {
                best = slot;
            }
        }

        if (best == null) {
            PerformanceOnlyStats.recordMissingLastRL();
            return new byte[blockSize];
        }

        return best.getData();
    }

    public BuildUpdResult buildUpd(oram.utils.Operation op,
                                int bid,
                                byte[] dataStar,
                                byte[] dataCur,
                                int seq,
                                int c,
                                int blockSize) {
        int pidR = generateRandomPid();
        int rid = seq % c;

        if (op == oram.utils.Operation.WRITE) {
            RLSlot slotCur = new RLSlot(
                    bid,
                    dataStar,
                    pidR,
                    seq,
                    false
            );

            UpdateMapEntry mpCur = new UpdateMapEntry(
                    bid,
                    rid,
                    pidR,
                    seq,
                    false
            );

            return new BuildUpdResult(slotCur, mpCur);
        }

        if (dataCur == null) {
            return new BuildUpdResult(
                    RLSlot.dummy(blockSize),
                    UpdateMapEntry.dummy()
            );
        }

        RLSlot slotCur = new RLSlot(
                bid,
                dataCur,
                pidR,
                seq,
                false
        );

        UpdateMapEntry mpCur = new UpdateMapEntry(
                bid,
                rid,
                pidR,
                seq,
                false
        );

        return new BuildUpdResult(slotCur, mpCur);
    }

}

class ReadDecision {
    private final int pid;
    private final boolean randomPath;

    ReadDecision(int pid, boolean randomPath) {
        this.pid = pid;
        this.randomPath = randomPath;
    }

    int getPid() {
        return pid;
    }

    boolean isRandomPath() {
        return randomPath;
    }
}

class ExtractCurResult {
    private final byte[] dataCur;
    private final DelayedRequest delayedRequest;

    ExtractCurResult(byte[] dataCur, DelayedRequest delayedRequest) {
        this.dataCur = dataCur;
        this.delayedRequest = delayedRequest;
    }

    byte[] getDataCur() {
        return dataCur;
    }

    DelayedRequest getDelayedRequest() {
        return delayedRequest;
    }
}

class BuildUpdResult {
    private final RLSlot slotCur;
    private final UpdateMapEntry mpCur;

    BuildUpdResult(RLSlot slotCur, UpdateMapEntry mpCur) {
        this.slotCur = slotCur;
        this.mpCur = mpCur;
    }

    RLSlot getSlotCur() {
        return slotCur;
    }

    UpdateMapEntry getMpCur() {
        return mpCur;
    }
}
