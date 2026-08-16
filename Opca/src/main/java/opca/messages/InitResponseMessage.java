package opca.messages;

import oram.common.Status;

public class InitResponseMessage {
    private final Status status;

    public InitResponseMessage(Status status) {
        this.status = status;
    }

    public Status getStatus() {
        return status;
    }

    public byte[] toBytes() {
        return new byte[]{(byte) status.ordinal()};
    }

    public static InitResponseMessage fromBytes(byte[] input) {
        int ordinal = Byte.toUnsignedInt(input[0]);
        return new InitResponseMessage(Status.getStatus(ordinal));
    }
}
