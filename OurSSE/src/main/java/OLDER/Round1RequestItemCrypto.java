package oram.client;

import oram.security.EncryptionAbstraction;
import oram.utils.Operation;
import oram.utils.ORAMUtils;

public class Round1RequestItemCrypto {
    private final EncryptionAbstraction encryption;

    public Round1RequestItemCrypto() {
        this.encryption = new EncryptionAbstraction("oram");
    }

    public byte[] encrypt(Round1RequestItem item) {
        byte[] plaintext = new byte[Integer.BYTES * 2];
        int offset = 0;

        ORAMUtils.serializeInteger(item.getOperation().ordinal(), plaintext, offset);
        offset += Integer.BYTES;

        ORAMUtils.serializeInteger(item.getBid(), plaintext, offset);

        byte[] ciphertext = encryption.encrypt(plaintext);
        if (ciphertext == null) {
            throw new IllegalStateException("Failed to encrypt Round1RequestItem");
        }

        return ciphertext;
    }

    public Round1RequestItem decrypt(byte[] ciphertext) {
        byte[] plaintext = encryption.decrypt(ciphertext);
        if (plaintext == null) {
            throw new IllegalStateException("Failed to decrypt Round1RequestItem");
        }

        int offset = 0;

        int opOrdinal = ORAMUtils.deserializeInteger(plaintext, offset);
        offset += Integer.BYTES;

        int bid = ORAMUtils.deserializeInteger(plaintext, offset);

        return new Round1RequestItem(Operation.values()[opOrdinal], bid);
    }
}
