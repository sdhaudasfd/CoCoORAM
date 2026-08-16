package oram.client;

public class EvictionAssignment {
    private final int seq;
    private final int slot;
    private final int pidE;
    private final int[] ownedBucketIndexes;
    private final int bidStartInclusive;
    private final int bidEndExclusive;
    private final int[] ownedLastRlSlots;

    public EvictionAssignment(int seq,
                              int slot,
                              int pidE,
                              int[] ownedBucketIndexes,
                              int bidStartInclusive,
                              int bidEndExclusive,
                              int[] ownedLastRlSlots) {
        this.seq = seq;
        this.slot = slot;
        this.pidE = pidE;
        this.ownedBucketIndexes = ownedBucketIndexes;
        this.bidStartInclusive = bidStartInclusive;
        this.bidEndExclusive = bidEndExclusive;
        this.ownedLastRlSlots = ownedLastRlSlots;
    }

    public int getSeq() {
        return seq;
    }

    public int getSlot() {
        return slot;
    }

    public int getPidE() {
        return pidE;
    }

    public int[] getOwnedBucketIndexes() {
        return ownedBucketIndexes;
    }

    public int getBidStartInclusive() {
        return bidStartInclusive;
    }

    public int getBidEndExclusive() {
        return bidEndExclusive;
    }

    public int[] getOwnedLastRlSlots() {
        return ownedLastRlSlots;
    }
}
