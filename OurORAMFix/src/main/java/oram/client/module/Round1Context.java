package oram.client.module;

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
    private final List<UpdateMapEntry[]> udMpHistory;
    private final int latestUdMpTimestep;
    private final RLSlot[] lastRL;
    private final RLSlot[] delayedRL;

    public Round1Context(int seq,
                         int timestep,
                         int slot,
                         List<Round1RequestItem> priorRequests,
                         Map<Integer, GlobalMapEntry> gbMp,
                         List<UpdateMapEntry[]> udMpHistory,
                         int latestUdMpTimestep,
                         RLSlot[] lastRL,
                         RLSlot[] delayedRL) {
        this.seq = seq;
        this.timestep = timestep;
        this.slot = slot;
        this.priorRequests = priorRequests;
        this.gbMp = gbMp;
        this.udMpHistory = udMpHistory;
        this.latestUdMpTimestep = latestUdMpTimestep;
        this.lastRL = lastRL;
        this.delayedRL = delayedRL;
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

    public List<UpdateMapEntry[]> getUdMpHistory() {
        return udMpHistory;
    }

    public int getLatestUdMpTimestep() {
        return latestUdMpTimestep;
    }

    public RLSlot[] getLastRL() {
        return lastRL;
    }

    public RLSlot[] getDelayedRL() {
        return delayedRL;
    }
}
