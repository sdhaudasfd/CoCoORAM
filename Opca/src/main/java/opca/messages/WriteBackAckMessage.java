package opca.messages;

import oram.common.ORAMUtils;

public class WriteBackAckMessage {
    private final int writeBackRound;

    public WriteBackAckMessage(int writeBackRound) {
        this.writeBackRound = writeBackRound;
    }

    public int getWriteBackRound() {
        return writeBackRound;
    }

    public byte[] toBytes() {
        byte[] out = new byte[Integer.BYTES];
        ORAMUtils.serializeInteger(writeBackRound, out, 0);
        return out;
    }

    public static WriteBackAckMessage fromBytes(byte[] input) {
        return new WriteBackAckMessage(ORAMUtils.deserializeInteger(input, 0));
    }
}
