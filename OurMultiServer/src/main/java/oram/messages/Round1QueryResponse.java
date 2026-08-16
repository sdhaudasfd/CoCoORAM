package oram.messages;

import oram.structure.EncryptedGlobalMapEntry;
import oram.utils.ORAMUtils;
import oram.utils.RawCustomExternalizable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Round1QueryResponse implements RawCustomExternalizable {
    private int seq;
    private int timestep;
    private int slot;
    private List<byte[]> priorEncryptedRequests;
    private Map<Integer, EncryptedGlobalMapEntry> encryptedGbMp;
    private byte[][] encryptedLastUdMp;
    private byte[][] encryptedLastRL;

    public Round1QueryResponse() {
        this.priorEncryptedRequests = new ArrayList<>();
        this.encryptedGbMp = new HashMap<>();
        this.encryptedLastUdMp = new byte[0][];
        this.encryptedLastRL = new byte[0][];
    }

    public Round1QueryResponse(int seq,
                               int timestep,
                               int slot,
                               List<byte[]> priorEncryptedRequests,
                               Map<Integer, EncryptedGlobalMapEntry> encryptedGbMp,
                               byte[][] encryptedLastUdMp,
                               byte[][] encryptedLastRL) {
        this.seq = seq;
        this.timestep = timestep;
        this.slot = slot;
        this.priorEncryptedRequests = copyEncryptedRequests(priorEncryptedRequests);
        this.encryptedGbMp = copyEncryptedGbMp(encryptedGbMp);
        this.encryptedLastUdMp = copyCipherArray(encryptedLastUdMp);
        this.encryptedLastRL = copyCipherArray(encryptedLastRL);
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

    public List<byte[]> getPriorEncryptedRequests() {
        return copyEncryptedRequests(priorEncryptedRequests);
    }

    public Map<Integer, EncryptedGlobalMapEntry> getEncryptedGbMp() {
        return copyEncryptedGbMp(encryptedGbMp);
    }

    public byte[][] getEncryptedLastUdMp() {
        return copyCipherArray(encryptedLastUdMp);
    }

    public byte[][] getEncryptedLastRL() {
        return copyCipherArray(encryptedLastRL);
    }

    @Override
    public int getSerializedSize() {
        int size = Integer.BYTES * 4;

        for (byte[] request : priorEncryptedRequests) {
            size += Integer.BYTES;
            size += request.length;
        }

        size += Integer.BYTES;
        for (Map.Entry<Integer, EncryptedGlobalMapEntry> entry : encryptedGbMp.entrySet()) {
            size += Integer.BYTES;
            size += entry.getValue().getSerializedSize();
        }

        size += Integer.BYTES;
        for (byte[] ciphertext : encryptedLastUdMp) {
            size += Integer.BYTES;
            size += ciphertext.length;
        }

        size += Integer.BYTES;
        for (byte[] ciphertext : encryptedLastRL) {
            size += Integer.BYTES;
            size += ciphertext.length;
        }

        return size;
    }

    @Override
    public int writeExternal(byte[] output, int startOffset) {
        int offset = startOffset;

        ORAMUtils.serializeInteger(seq, output, offset);
        offset += Integer.BYTES;

        ORAMUtils.serializeInteger(timestep, output, offset);
        offset += Integer.BYTES;

        ORAMUtils.serializeInteger(slot, output, offset);
        offset += Integer.BYTES;

        ORAMUtils.serializeInteger(priorEncryptedRequests.size(), output, offset);
        offset += Integer.BYTES;
        for (byte[] request : priorEncryptedRequests) {
            ORAMUtils.serializeInteger(request.length, output, offset);
            offset += Integer.BYTES;
            System.arraycopy(request, 0, output, offset, request.length);
            offset += request.length;
        }

        ORAMUtils.serializeInteger(encryptedGbMp.size(), output, offset);
        offset += Integer.BYTES;
        for (Map.Entry<Integer, EncryptedGlobalMapEntry> entry : encryptedGbMp.entrySet()) {
            ORAMUtils.serializeInteger(entry.getKey(), output, offset);
            offset += Integer.BYTES;
            offset = entry.getValue().writeExternal(output, offset);
        }

        ORAMUtils.serializeInteger(encryptedLastUdMp.length, output, offset);
        offset += Integer.BYTES;
        for (byte[] ciphertext : encryptedLastUdMp) {
            ORAMUtils.serializeInteger(ciphertext.length, output, offset);
            offset += Integer.BYTES;
            System.arraycopy(ciphertext, 0, output, offset, ciphertext.length);
            offset += ciphertext.length;
        }

        ORAMUtils.serializeInteger(encryptedLastRL.length, output, offset);
        offset += Integer.BYTES;
        for (byte[] ciphertext : encryptedLastRL) {
            ORAMUtils.serializeInteger(ciphertext.length, output, offset);
            offset += Integer.BYTES;
            System.arraycopy(ciphertext, 0, output, offset, ciphertext.length);
            offset += ciphertext.length;
        }

        return offset;
    }

    @Override
    public int readExternal(byte[] input, int startOffset) {
        int offset = startOffset;

        seq = ORAMUtils.deserializeInteger(input, offset);
        offset += Integer.BYTES;

        timestep = ORAMUtils.deserializeInteger(input, offset);
        offset += Integer.BYTES;

        slot = ORAMUtils.deserializeInteger(input, offset);
        offset += Integer.BYTES;

        int requestCount = ORAMUtils.deserializeInteger(input, offset);
        offset += Integer.BYTES;
        priorEncryptedRequests = new ArrayList<>(requestCount);
        for (int i = 0; i < requestCount; i++) {
            int length = ORAMUtils.deserializeInteger(input, offset);
            offset += Integer.BYTES;

            byte[] request = new byte[length];
            System.arraycopy(input, offset, request, 0, length);
            offset += length;

            priorEncryptedRequests.add(request);
        }

        int gbMpSize = ORAMUtils.deserializeInteger(input, offset);
        offset += Integer.BYTES;
        encryptedGbMp = new HashMap<>(gbMpSize);
        for (int i = 0; i < gbMpSize; i++) {
            int bid = ORAMUtils.deserializeInteger(input, offset);
            offset += Integer.BYTES;

            EncryptedGlobalMapEntry entry = new EncryptedGlobalMapEntry();
            offset = entry.readExternal(input, offset);
            encryptedGbMp.put(bid, entry);
        }

        int lastUdMpSize = ORAMUtils.deserializeInteger(input, offset);
        offset += Integer.BYTES;
        encryptedLastUdMp = new byte[lastUdMpSize][];
        for (int i = 0; i < lastUdMpSize; i++) {
            int length = ORAMUtils.deserializeInteger(input, offset);
            offset += Integer.BYTES;

            encryptedLastUdMp[i] = new byte[length];
            System.arraycopy(input, offset, encryptedLastUdMp[i], 0, length);
            offset += length;
        }

        int lastRLSize = ORAMUtils.deserializeInteger(input, offset);
        offset += Integer.BYTES;
        encryptedLastRL = new byte[lastRLSize][];
        for (int i = 0; i < lastRLSize; i++) {
            int length = ORAMUtils.deserializeInteger(input, offset);
            offset += Integer.BYTES;

            encryptedLastRL[i] = new byte[length];
            System.arraycopy(input, offset, encryptedLastRL[i], 0, length);
            offset += length;
        }

        return offset;
    }

    private static List<byte[]> copyEncryptedRequests(List<byte[]> source) {
        List<byte[]> copy = new ArrayList<>(source.size());
        for (byte[] request : source) {
            byte[] requestCopy = new byte[request.length];
            System.arraycopy(request, 0, requestCopy, 0, request.length);
            copy.add(requestCopy);
        }
        return copy;
    }

    private static Map<Integer, EncryptedGlobalMapEntry> copyEncryptedGbMp(
            Map<Integer, EncryptedGlobalMapEntry> source) {
        Map<Integer, EncryptedGlobalMapEntry> copy = new HashMap<>(source.size());

        for (Map.Entry<Integer, EncryptedGlobalMapEntry> entry : source.entrySet()) {
            byte[] ciphertext = entry.getValue().getCiphertext();
            byte[] ciphertextCopy = new byte[ciphertext.length];
            System.arraycopy(ciphertext, 0, ciphertextCopy, 0, ciphertext.length);
            copy.put(entry.getKey(), new EncryptedGlobalMapEntry(ciphertextCopy));
        }

        return copy;
    }

    private static byte[][] copyCipherArray(byte[][] source) {
        byte[][] copy = new byte[source.length][];
        for (int i = 0; i < source.length; i++) {
            byte[] ciphertext = source[i];
            byte[] ciphertextCopy = new byte[ciphertext.length];
            System.arraycopy(ciphertext, 0, ciphertextCopy, 0, ciphertext.length);
            copy[i] = ciphertextCopy;
        }
        return copy;
    }
}
