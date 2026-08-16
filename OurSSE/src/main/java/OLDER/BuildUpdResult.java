package oram.client;

import oram.structure.RLSlot;
import oram.structure.UpdateMapEntry;

public class BuildUpdResult {
    private final RLSlot slotCur;
    private final UpdateMapEntry mpCur;

    public BuildUpdResult(RLSlot slotCur, UpdateMapEntry mpCur) {
        this.slotCur = slotCur;
        this.mpCur = mpCur;
    }

    public RLSlot getSlotCur() {
        return slotCur;
    }

    public UpdateMapEntry getMpCur() {
        return mpCur;
    }
}
