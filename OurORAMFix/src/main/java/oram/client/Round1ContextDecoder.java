package oram.client;

import oram.client.module.Round1Context;
import oram.client.module.Round1RequestItem;
import oram.messages.Round1QueryResponse;
import oram.security.ProtocolEncryptionManager;
import oram.structure.EncryptedGlobalMapEntry;
import oram.structure.GlobalMapEntry;
import oram.structure.RLSlot;
import oram.structure.UpdateMapEntry;
import oram.utils.Operation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Round1ContextDecoder {
    private final Round1RequestItemCrypto round1Crypto;
    private final ProtocolEncryptionManager encryptionManager;

    public Round1ContextDecoder() {
        this.round1Crypto = new Round1RequestItemCrypto();
        this.encryptionManager = new ProtocolEncryptionManager();
    }

    public Round1Context decode(Round1QueryResponse response) {
        List<Round1RequestItem> priorRequests = decodePriorRequests(response.getPriorEncryptedRequests());
        Map<Integer, GlobalMapEntry> gbMp = decodeGbMp(response.getEncryptedGbMp());
        List<UpdateMapEntry[]> udMpHistory = decodeUdMpHistory(response.getEncryptedUdMpHistory());
        RLSlot[] lastRL = decodeLastRL(response.getEncryptedLastRL());
        RLSlot[] delayedRL = decodeLastRL(response.getEncryptedDelayedRL());

        return new Round1Context(
                response.getSeq(),
                response.getTimestep(),
                response.getSlot(),
                priorRequests,
                gbMp,
                udMpHistory,
                response.getLatestUdMpTimestep(),
                lastRL,
                delayedRL
        );
    }

    public boolean containsBid(List<Round1RequestItem> requests, int bid) {
        for (Round1RequestItem item : requests) {
            if (item.getBid() == bid) {
                return true;
            }
        }
        return false;
    }

    public boolean containsWriteForBid(List<Round1RequestItem> requests, int bid) {
        for (Round1RequestItem item : requests) {
            if (item.getBid() == bid && item.getOperation() == Operation.WRITE) {
                return true;
            }
        }
        return false;
    }

    private List<Round1RequestItem> decodePriorRequests(List<byte[]> encryptedRequests) {
        List<Round1RequestItem> decoded = new ArrayList<>(encryptedRequests.size());

        for (byte[] encryptedRequest : encryptedRequests) {
            decoded.add(round1Crypto.decrypt(encryptedRequest));
        }

        return decoded;
    }

    private Map<Integer, GlobalMapEntry> decodeGbMp(Map<Integer, EncryptedGlobalMapEntry> encryptedGbMp) {
        Map<Integer, GlobalMapEntry> decoded = new HashMap<>(encryptedGbMp.size());

        for (Map.Entry<Integer, EncryptedGlobalMapEntry> entry : encryptedGbMp.entrySet()) {
            decoded.put(entry.getKey(), encryptionManager.decryptGlobalMapEntry(entry.getValue()));
        }

        return decoded;
    }

    private List<UpdateMapEntry[]> decodeUdMpHistory(List<byte[][]> encryptedHistory) {
        List<UpdateMapEntry[]> decodedHistory = new ArrayList<>(encryptedHistory.size());
        for (byte[][] encryptedVersion : encryptedHistory) {
            UpdateMapEntry[] decoded = new UpdateMapEntry[encryptedVersion.length];

            for (int i = 0; i < encryptedVersion.length; i++) {
                decoded[i] = encryptionManager.decryptUpdateMapEntry(encryptedVersion[i]);
            }
            decodedHistory.add(decoded);
        }

        return decodedHistory;
    }

    private RLSlot[] decodeLastRL(byte[][] encryptedLastRL) {
        RLSlot[] decoded = new RLSlot[encryptedLastRL.length];

        for (int i = 0; i < encryptedLastRL.length; i++) {
            decoded[i] = encryptionManager.decryptRLSlot(encryptedLastRL[i]);
        }

        return decoded;
    }
}
