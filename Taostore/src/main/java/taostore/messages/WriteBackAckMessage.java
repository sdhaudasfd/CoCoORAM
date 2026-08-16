package taostore.messages;

import oram.common.ORAMUtils;

public class WriteBackAckMessage {
    private final int serverTimestamp;

    public WriteBackAckMessage(int serverTimestamp) {
        this.serverTimestamp = serverTimestamp;
    }

    public int getServerTimestamp() {
        return serverTimestamp;
    }

    public byte[] toBytes() {
        byte[] out = new byte[Integer.BYTES];
        ORAMUtils.serializeInteger(serverTimestamp, out, 0);
        return out;
    }

    public static WriteBackAckMessage fromBytes(byte[] input) {
        return new WriteBackAckMessage(ORAMUtils.deserializeInteger(input, 0));
    }
}
