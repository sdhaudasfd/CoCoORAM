package oram.client;

import oram.structure.GlobalMapEntry;
import oram.structure.ORAMPath;

import java.util.Map;

public class BuildEvictionResult {
    private final ORAMPath pathPrime;
    private final Map<Integer, GlobalMapEntry> mpPrime;

    public BuildEvictionResult(ORAMPath pathPrime, Map<Integer, GlobalMapEntry> mpPrime) {
        this.pathPrime = pathPrime;
        this.mpPrime = mpPrime;
    }

    public ORAMPath getPathPrime() {
        return pathPrime;
    }

    public Map<Integer, GlobalMapEntry> getMpPrime() {
        return mpPrime;
    }
}
