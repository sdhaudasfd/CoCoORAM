package oram.utils;

public enum ServerOperationType {
    INIT_POSITION_MAP,
    REGISTER_ACCESS,
    READ_PATH,
    SUBMIT_TURN,
    DUMMY_TURN,
    WAIT_TURN_UPDATE;

    public static final ServerOperationType[] values = values();

    public static ServerOperationType getOperation(int ordinal) {
        return values[ordinal];
    }
}
