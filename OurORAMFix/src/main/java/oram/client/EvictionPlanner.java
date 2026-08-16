package oram.client;

public class EvictionPlanner {
    private final int leafCount;
    private final int treeHeight;
    private final int c;
    private final int bidSpace;

    public EvictionPlanner(int leafCount, int treeHeight, int c, int bidSpace) {
        this.leafCount = leafCount;
        this.treeHeight = treeHeight;
        this.c = c;
        this.bidSpace = bidSpace;
    }

    public EvictionAssignment plan(int seq) {
        int slot = seq % c;
        int pidE = digitReverse(seq % leafCount, treeHeight - 1);
        int[] bidRange = computeBidRange(slot);

        return new EvictionAssignment(
                seq,
                slot,
                pidE,
                bidRange[0],
                bidRange[1]
        );
    }

    private int[] computeBidRange(int slot) {
        int base = bidSpace / c;
        int remainder = bidSpace % c;

        int start = slot * base + Math.min(slot, remainder);
        int length = base + (slot < remainder ? 1 : 0);
        int end = start + length;

        return new int[] { start, end };
    }

    private int digitReverse(int value, int bits) {
        return Integer.reverse(value) >>> (Integer.SIZE - bits);
    }
}

class EvictionAssignment {
    private final int seq;
    private final int slot;
    private final int pidE;
    private final int bidStartInclusive;
    private final int bidEndExclusive;

    EvictionAssignment(int seq,
                       int slot,
                       int pidE,
                       int bidStartInclusive,
                       int bidEndExclusive) {
        this.seq = seq;
        this.slot = slot;
        this.pidE = pidE;
        this.bidStartInclusive = bidStartInclusive;
        this.bidEndExclusive = bidEndExclusive;
    }

    int getSeq() {
        return seq;
    }

    int getSlot() {
        return slot;
    }

    int getPidE() {
        return pidE;
    }

    int getBidStartInclusive() {
        return bidStartInclusive;
    }

    int getBidEndExclusive() {
        return bidEndExclusive;
    }
}
