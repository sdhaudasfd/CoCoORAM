package oram.client;

import oram.structure.GlobalMapEntry;
import oram.structure.RLSlot;
import oram.structure.UpdateMapEntry;

import java.util.List;
import java.util.Map;

public class Round1Context {
    private final int seq;
    private final int timestep;
    private final int slot;
    private final List<Round1RequestItem> priorRequests;
    private final Map<Integer, GlobalMapEntry> gbMp;
    private final UpdateMapEntry[] lastUdMp;
    private final RLSlot[] lastRL;

    public Round1Context(int seq,
                         int timestep,
                         int slot,
                         List<Round1RequestItem> priorRequests,
                         Map<Integer, GlobalMapEntry> gbMp,
                         UpdateMapEntry[] lastUdMp,
                         RLSlot[] lastRL) {
        this.seq = seq;
        this.timestep = timestep;
        this.slot = slot;
        this.priorRequests = priorRequests;
        this.gbMp = gbMp;
        this.lastUdMp = lastUdMp;
        this.lastRL = lastRL;
    }

    public int getSeq() {
        return seq;
    }

    public int getTimestep() {
        return timestep;
    }

    public int getSlot() {
        return slot;
    }

    public List<Round1RequestItem> getPriorRequests() {
        return priorRequests;
    }

    public Map<Integer, GlobalMapEntry> getGbMp() {
        return gbMp;
    }

    public UpdateMapEntry[] getLastUdMp() {
        return lastUdMp;
    }

    public RLSlot[] getLastRL() {
        return lastRL;
    }
}
