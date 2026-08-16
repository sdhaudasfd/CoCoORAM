package taostore.messages;

import oram.common.Status;

public class InitOramResponseMessage {
    private final Status status;

    public InitOramResponseMessage(Status status) {
        this.status = status;
    }

    public Status getStatus() {
        return status;
    }

    public byte[] toBytes() {
        return new byte[]{(byte) status.ordinal()};
    }

    public static InitOramResponseMessage fromBytes(byte[] input) {
        int ordinal = Byte.toUnsignedInt(input[0]);
        return new InitOramResponseMessage(Status.getStatus(ordinal));
    }
}
