package opca.proxy.structure;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class OpcaMap {
    private final ConcurrentHashMap<Integer, CopyOnWriteArrayList<OpcaMapEntry>> versionLists;

    public OpcaMap() {
        this.versionLists = new ConcurrentHashMap<>();
    }

    public synchronized void addVersion(int address, int bufIndex, long timestamp) {
        versionLists.computeIfAbsent(address, ignored -> new CopyOnWriteArrayList<>())
                .add(new OpcaMapEntry(bufIndex, timestamp));
    }

    public OpcaMapEntry getLatestVersion(int address) {
        List<OpcaMapEntry> versions = versionLists.get(address);
        if (versions == null || versions.isEmpty()) {
            return null;
        }
        return versions.stream().max(Comparator.comparingLong(OpcaMapEntry::getTimestamp)).orElse(null);
    }

    public List<OpcaMapEntry> getAllVersions(int address) {
        List<OpcaMapEntry> versions = versionLists.get(address);
        if (versions == null) {
            return Collections.emptyList();
        }
        return new ArrayList<>(versions);
    }

    public Set<Integer> dirtyAddresses() {
        return versionLists.keySet();
    }

    public synchronized void clear() {
        versionLists.clear();
    }

    public boolean hasDirtyVersion(int address) {
        List<OpcaMapEntry> versions = versionLists.get(address);
        return versions != null && !versions.isEmpty();
    }

    public int totalEntries() {
        int total = 0;
        for (List<OpcaMapEntry> entries : versionLists.values()) {
            total += entries.size();
        }
        return total;
    }
}
