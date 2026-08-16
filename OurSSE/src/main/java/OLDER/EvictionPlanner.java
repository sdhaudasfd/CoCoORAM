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

        int[] ownedBucketIndexes = computeOwnedBucketIndexes(seq, slot, pidE);
        int[] bidRange = computeBidRange(slot);
        int[] ownedLastRlSlots = new int[] { slot };

        return new EvictionAssignment(
                seq,
                slot,
                pidE,
                ownedBucketIndexes,
                bidRange[0],
                bidRange[1],
                ownedLastRlSlots
        );
    }

    private int[] computeOwnedBucketIndexes(int seq, int mySlot, int myPidE) {
        boolean[] owned = new boolean[treeHeight];

        for (int level = 0; level < treeHeight; level++) {
            owned[level] = isOwnerForLevel(seq, mySlot, myPidE, level);
        }

        int count = 0;
        for (boolean value : owned) {
            if (value) {
                count++;
            }
        }

        int[] indexes = new int[count];
        int idx = 0;
        for (int level = 0; level < treeHeight; level++) {
            if (owned[level]) {
                indexes[idx++] = level;
            }
        }

        return indexes;
    }

    private boolean isOwnerForLevel(int seq, int mySlot, int myPidE, int level) {
        int timestepBaseSeq = seq - mySlot;
        int myPrefix = prefixAtLevel(myPidE, level);

        for (int otherSlot = 0; otherSlot < c; otherSlot++) {
            int otherSeq = timestepBaseSeq + otherSlot;
            int otherPidE = digitReverse(otherSeq % leafCount, treeHeight - 1);
            int otherPrefix = prefixAtLevel(otherPidE, level);

            if (otherPrefix == myPrefix && otherSlot < mySlot) {
                return false;
            }
        }

        return true;
    }

    private int[] computeBidRange(int slot) {
        int base = bidSpace / c;
        int remainder = bidSpace % c;

        int start = slot * base + Math.min(slot, remainder);
        int length = base + (slot < remainder ? 1 : 0);
        int end = start + length;

        return new int[] { start, end };
    }

    private int prefixAtLevel(int pid, int level) {
        int shift = (treeHeight - 1) - level;
        return pid >> shift;
    }

    private int digitReverse(int value, int bits) {
        int result = 0;
        for (int i = 0; i < bits; i++) {
            result <<= 1;
            result |= (value >> i) & 1;
        }
        return result;
    }
}
